(in-ns 'dj.io)

(defn file
  "returns a new java.io.File with args as files or str-paths"
  ^java.io.File
  [& paths]
  (java.io.File. ^String (apply dj/str-path (map (fn [p]
						   (if (= (type p)
							  java.io.File)
						     (.getPath ^java.io.File p)
						     p))
						 paths))))

(defmethod clojure.core/print-method java.io.File [^java.io.File o ^java.io.Writer writer]
  (.write writer
          (str "#java.io.File[\"" (.getPath o) "\"]")))

(defn absolute-path? [str-path]
  (= (first str-path)
	 \/))

(defn sh-exception [result]
  (if (= (:exit result) 0)
    (:out result)
    (throw (Exception. ^java.lang.String (:err result)))))

(defn- shify [args]
  (apply str (interpose " " args)))

(defn ssh [username server port sh-code & args]
  (apply sh/sh
	 "ssh" (str username "@" server) "-p" (str port)
	 "sh" "-c"
	 (str "'" sh-code "'")
	 args))

(extend-type java.io.File
  Ieat
  (eat [this] (slurp this))
  Ipoop
  (poop ([this txt append]
	   ;; not portable
	   (if append
	     (sh-exception (sh/sh "sh" "-c" (str "cat - >> " (.getPath this)) :in txt))
	     (spit this txt)))
	([this txt]
	   (spit this txt)))
  Imkdir
  (mkdir [this] (when-not (.exists this)
                  (if (.mkdirs this)
                    this
                    (throw (ex-info (str "Could not make directory " (.getCanonicalPath this))
                                    {:file this})))))
  Iget-name
  (get-name [f]
	    (let [n (.getName f)]
	      (if (empty? n)
		nil
		n)))
  Ils
  (ls [dest]
      (seq (.listFiles dest)))
  Irm
  (rm [dest]
      (when (exists? dest)
	(when (.isDirectory dest)
	 (doseq [f (ls dest)]
	   (rm f)))
	(.delete dest)))
  Imv
  (mv [target ^java.io.File dest]
    (when-not (if (.isDirectory dest)
                (.renameTo target (file dest (.getName target)))
                (.renameTo target dest))
      (if (.exists dest)
        (throw (ex-info (str "Failed to move file " (.getCanonicalPath target) " to file " (.getCanonicalPath dest) " because destination already exists")
                        {:target target
                         :dest dest}))
        (if (.exists (.getParentFile dest))
          (throw (ex-info (str "Failed to move file " (.getCanonicalPath target) " to file/directory " (.getCanonicalPath dest))
                          {:target target
                           :dest dest}))
          (throw (ex-info (str "Failed to move file " (.getCanonicalPath target) " to file/directory " (.getCanonicalPath dest) " because destination folder does not exist")
                          {:target target
                           :dest dest}))))))
  Irelative-to
  (relative-to [folder path]
	       (file folder path))
  Iparent
  (parent [f]
	  (.getParentFile f))
  Iget-path
  (get-path [f]
	    (.getPath f))
  Iexists
  (exists? [f]
	   (.exists f)))

(defrecord remote-file [path username server port]
  Ipoop
  (poop [dest txt append]
    (if append
      (ssh username server port (str "cat - >> " path) :in txt)
      (ssh username server port (str "cat - > " path) :in txt)))
  (poop [dest txt]
    (ssh username server port (str "cat - > " path) :in txt))
  Ieat
  (eat [dest]
    (let [result (ssh username server port (shify ["cat" path]))]
      (if (zero? (:exit result))
        (:out result)
        (throw (Exception. ^java.lang.String (:err result))))))
  Imkdir
  (mkdir [dest]
    (if (zero? (:exit (ssh username server port (str "mkdir -p " path))))
      dest
      (throw (Exception. (str "Could not make remote directory " path)))))
  Iget-name
  (get-name [f]
    (last (.split ^String (:path f) "/")))
  Ils
  (ls [dest]
    (map #(remote-file. (str path "/" %) username server port)
         (let [ls-str (:out (ssh username server port (shify ["ls" path])))]
           (if (empty? ls-str)
             nil
             (.split ^String ls-str "\n")))))
  Irm
  (rm [target] (sh-exception
		(ssh username server port (shify ["rm" "-rf" path]))))
  Irelative-to
  (relative-to [folder path]
    (remote-file. (dj/str-path (:path folder) path)
                  username server port))
  Imv
  (mv [target dest]
    (ssh username server port (shify ["mv" path dest])))
  Iparent
  (parent [f]
    (assoc f
      :path
      (apply str (if (= (first (:path f))
                        \/)
                   "/"
                   "")
             (interpose "/" (filter #(not (empty? %)) (drop-last (.split ^String (:path f) "/")))))))
  Iget-path
  (get-path [f]
    path)
  Iexists
  (exists? [f]
    (zero?
     (:exit
      (ssh username server port
           (str "test -e " path))))))

(defn file-channel-copy [^java.io.File source ^java.io.File dest]
  (when-not (.exists dest)
    (.createNewFile dest))
  (with-open [s (.getChannel (java.io.FileInputStream. source))
              d (.getChannel (java.io.FileOutputStream. dest))]
    (.transferFrom d
                   s
                   0
                   (.size s))))

(defmethod cp [java.lang.String java.lang.String] [^java.lang.String in ^java.lang.String out]
	   (sh-exception
	    (sh/sh "cp" "-R" in out)))

(defmethod cp [java.io.File java.io.File] [^java.io.File in ^java.io.File out]
	   (sh-exception
	    (sh/sh "cp" "-R" (.getCanonicalPath in) (.getCanonicalPath out))))

(defmethod cp [remote-file java.io.File] [in ^java.io.File out]
	   (let [{:keys [path username server port]} in]
	     (sh-exception
	      (sh/sh "ionice" "-c" "3" "scp" "-r" "-P" (str port) (str username "@" server ":" path) (.getCanonicalPath out)))))

(defmethod cp [java.io.File remote-file] [^java.io.File in out]
	   (let [{:keys [path username server port]} out]
	     (sh-exception
	      (sh/sh "ionice" "-c" "3" "scp" "-r" "-P" (str port) (.getCanonicalPath in) (str username "@" server ":" path)))))

(defmethod cp [remote-file java.lang.String] [in ^java.lang.String out]
	   (let [{:keys [path username server port]} in]
	     (sh-exception
	      (sh/sh "ionice" "-c" "3" "scp" "-r" "-P" (str port) (str username "@" server ":" path) out))))

(defmethod cp [java.lang.String remote-file] [^java.lang.String in out]
	   (let [{:keys [path username server port]} out]
	     (sh-exception
	      (sh/sh "ionice" "-c" "3" "scp" "-r" "-P" (str port) in (str username "@" server ":" path)))))

(defmethod cp [remote-file remote-file] [in out]
  (throw (Exception. "not implemented")))

(defmacro capture-out-err [& body]
  `(let [o# (java.io.StringWriter.)
         e# (java.io.StringWriter.)]
     (binding [*out* o#
               *err* e#]
       (let [r# ~@body]
         {:return r#
          :out (str o#)
          :error (str e#)}))))

(defn new-persistent-ref
    "returns a ref with a watcher that writes to file contents of ref
as it changes. Write file is considered a 'cache', updates as best as
possible without slowing down ref"
    ([file-path the-agent]
       (let [f (file file-path)
	     writer-queue the-agent
	     dirty (ref false)
	     r (ref (load-file file-path))
	     clean (fn []
		     (dosync (ref-set dirty false))
		     (poop f
			   (binding [*print-dup* true]
			     (prn-str @r))))]
	 (add-watch r :writer (fn [k r old-state new-state]
				(dosync
				 (when-not @dirty
				   (ref-set dirty true)
				   (send-off writer-queue (fn [_]
							    (clean)))))))
	 r))
    ([file-path]
       (new-persistent-ref file-path (agent nil)))
    ([file-path the-agent default-value]
       (let [^java.io.File f (file file-path)]
	 (when-not (.exists f)
	   (poop f
		 (binding [*print-dup* true]
		   (prn-str default-value)))))
       (new-persistent-ref file-path the-agent)))

(defn to-byte-array [^java.io.File x]
  (with-open [buffer (java.io.ByteArrayOutputStream.)]
    (clojure.java.io/copy x buffer)
    (.toByteArray buffer)))

(defn pr-str
  "like clojure.core/pr-str but doesn't use rebinding to print to string"
  [obj]
  (let [s (java.io.StringWriter.)]
    (clojure.core/print-method obj s)
    (str s)))

(defn assoc-data-reader!
  "assocs clojure.core/*data-readers* to include new reader-fn for sym

reader-fn should accept the printable form"
  [sym reader-fn]
  (set! clojure.core/*data-readers*
        (assoc clojure.core/*data-readers*
          sym
          reader-fn)))

(defmacro with-temp-file [filesym options & body]
  `(let [options# (merge {:prefix "djtmp"
			  :suffix "tmp"
			  :read-fn slurp}
			 ~options)
	 ~filesym (java.io.File/createTempFile (:prefix options#)
					       (:suffix options#))
	 ret# (promise)]
     (try
       ~@body
       (finally
	(deliver ret#
		 ((:read-fn options#) ~filesym))
	(rm ~filesym)))
     @ret#))

(defn cstring [s]
  (let [cstring-length (inc (count s))
	a (byte-array cstring-length)]
    (System/arraycopy (.getBytes s)
		      0
		      a
		      0
		      (count s))
    (aset-byte a
	       (count s)
	       0)
    a))

(defn c-eat-binary-file
  "not for large files, appends null character"
  [^java.io.File file]
  (io! (with-open [reader (clojure.java.io/input-stream file)]
         (let [buffer (byte-array (inc (.length file)))]
           (.read reader buffer)
	   (aset-byte buffer
		      (.length file)
		      0)
           buffer))))

(defn binary->file
  "
outputs binary-array to a file
"
  [binary-array ^java.io.File f]
  (with-open [fos (java.io.FileOutputStream. f)]
    (.write fos binary-array))
  f)

(defn ^java.net.URI file->local-zip-uri
  "alpha"
  [^java.io.File file]
  (java.net.URI. (-> (str "jar:file:///"
                          (second (.split #"file:/+" (.toString (.toURI file)))))
                     (dj/replace-map {"%20" "%2520"}))))

(defn ^java.net.URI file->local-uri
  "alpha"
  [^java.io.File file]
  (java.net.URI. (-> (str "file:///"
                          (second (.split #"file:/+" (.toString (.toURI file)))))
                     #_ (dj/replace-map {"%20" "%2520"}))))

(dj/compile-time-if (re-find #"^1\.[0-6]" (System/getProperty "java.version"))
                    (do
                      (defn unzip [^java.io.File file ^java.io.File dest-dir]
                        (throw (Exception. "java7 required")))
                      (defn eat-binary-file
                        "not for large files"
                        [^java.io.File file]
                        (io! (with-open [reader (clojure.java.io/input-stream file)]
                               (let [buffer (byte-array (.length file))]
                                 (.read reader buffer)
                                 buffer)))))
                    (do
                      (defn unzip [^java.io.File file ^java.io.File dest-dir]
                        (let [zip-fs (java.nio.file.FileSystems/newFileSystem (file->local-zip-uri file)
                                                                              (java.util.HashMap.))
                              no-follow-links (into-array java.nio.file.LinkOption
                                                          [java.nio.file.LinkOption/NOFOLLOW_LINKS])]
                          (try
                            (let [dest-dir-path (java.nio.file.Paths/get (file->local-uri dest-dir))]
                              (when (java.nio.file.Files/notExists dest-dir-path
                                                                   no-follow-links)
                                (java.nio.file.Files/createDirectory dest-dir-path (into-array java.nio.file.attribute.FileAttribute
                                                                                               [])))
                              (let [root (.getPath zip-fs "/" (into-array String []))]
                                (java.nio.file.Files/walkFileTree root
                                                                  (proxy [java.nio.file.SimpleFileVisitor] []
                                                                    (visitFile [file-path attrs]
                                                                      (java.nio.file.Files/copy file-path
                                                                                                (java.nio.file.Paths/get (.toString dest-dir-path)
                                                                                                                         (into-array String [(.toString file-path)]))
                                                                                                (into-array java.nio.file.StandardCopyOption
                                                                                                            [java.nio.file.StandardCopyOption/REPLACE_EXISTING]))
                                                                      java.nio.file.FileVisitResult/CONTINUE)
                                                                    (preVisitDirectory [dir-path attrs]
                                                                      (let [dir-to-create (java.nio.file.Paths/get (.toString dest-dir-path)
                                                                                                                   (into-array String [(.toString dir-path)]))]
                                                                        (when (java.nio.file.Files/notExists dir-to-create
                                                                                                             no-follow-links)
                                                                          (java.nio.file.Files/createDirectory dir-to-create
                                                                                                               (into-array java.nio.file.attribute.FileAttribute
                                                                                                                           []))))
                                                                      java.nio.file.FileVisitResult/CONTINUE)))))
                            (finally
                             (.close zip-fs)))))
                      (defn eat-binary-file
                        "not for large files"
                        [^java.io.File file]
                        (java.nio.file.Files/readAllBytes (java.nio.file.Paths/get
                                                           (get-path file)
                                                           (into-array String []))))))

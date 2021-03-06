(ns mach.pack.alpha.one-jar
  (:require
   [clojure.java.io :as io]
   [clojure.string :as string]
   [clojure.tools.cli :as cli]
   [clojure.tools.deps.alpha :as tools.deps]
   [clojure.tools.deps.alpha.reader :as tools.deps.reader]
   [clojure.tools.deps.alpha.script.make-classpath]
   [mach.pack.alpha.impl.assembly :refer [spit-jar!]]
   [mach.pack.alpha.impl.elodin :as elodin]
   [me.raynes.fs :as fs])
  (:import
   java.io.File
   [java.nio.file Files Paths]
   java.nio.file.attribute.FileAttribute
   java.util.Arrays
   [javax.tools Diagnostic$Kind DiagnosticCollector ToolProvider]))

(defn by-ext
  [f ext]
  (.endsWith (.getName f) (str "." ext)))

(defn javac
  "Compile java sources. Primitive version for building a self-contained bootstrap"
  [tgt
   options] ;; options passed to java compiler
  (.mkdirs tgt)
  (let [diag-coll (DiagnosticCollector.)
        compiler  (or (ToolProvider/getSystemJavaCompiler)
                      (throw (Exception. "The java compiler is not working. Please make sure you use a JDK!")))]
    (with-open [file-mgr (.getStandardFileManager compiler diag-coll nil nil)]
      (let [file-mgr (.getStandardFileManager compiler diag-coll nil nil)
            opts (->> ["-d"  (.getPath tgt)]
                      (concat options)
                      (into-array String)
                      Arrays/asList)

            bootstrap
            (map
              #(let [file (io/resource %)]
                 (proxy [javax.tools.SimpleJavaFileObject]
                   [(java.net.URI. (str "string://" (string/replace % #"mach/pack" "")))
                    javax.tools.JavaFileObject$Kind/SOURCE]
                   (getCharContent [ignoredEncodingErrors]
                     (slurp file))))
              #_(comment (into [] (comp (filter (memfn isFile)) (map #(string/replace % #"^src/" ""))) (file-seq (io/file "src/mach/pack/alpha/bootstrap/onejar/src/"))))
              ["mach/pack/alpha/bootstrap/onejar/src/com/simontuffs/onejar/IProperties.java" "mach/pack/alpha/bootstrap/onejar/src/com/simontuffs/onejar/JarClassLoader.java" "mach/pack/alpha/bootstrap/onejar/src/com/simontuffs/onejar/Handler.java" "mach/pack/alpha/bootstrap/onejar/src/com/simontuffs/onejar/OneJarURLConnection.java" "mach/pack/alpha/bootstrap/onejar/src/com/simontuffs/onejar/OneJarFile.java" "mach/pack/alpha/bootstrap/onejar/src/com/simontuffs/onejar/Boot.java" "mach/pack/alpha/bootstrap/onejar/src/OneJar.java"])]
        (-> compiler
            (.getTask *err* file-mgr diag-coll opts nil bootstrap)
            (.call))
        (let [diagnostics (.getDiagnostics diag-coll)]
          (doseq [d diagnostics
                  :let [k (.getKind d)]]
            (let [log #(println (apply format %&))]
              (if (nil? (.getSource d))
                (log "%s: %s\n"
                     (.toString k)
                     (.getMessage d nil))
                (log "%s: %s, line %d: %s\n"
                     (.toString k)
                     (.. d getSource getName)
                     (.getLineNumber d)
                     (.getMessage d nil)))))
          (when-first [_ (filter #(= Diagnostic$Kind/ERROR (.getKind %))
                                 diagnostics)]
            (throw (Exception. "java compiler error"))))))))

(defn- deleting-tmp-dir
  [prefix]
  (let [tmp-path (Files/createTempDirectory prefix
                                            (into-array FileAttribute []))]
    (.addShutdownHook (Runtime/getRuntime)
                      (Thread.
                        (fn []
                          (fs/delete-dir (.toFile tmp-path)))))
    tmp-path))

(defn- create-bootstrap
  []
  (let [bootstrap-p (deleting-tmp-dir "pack-bootstrap")]
    (javac (.toFile bootstrap-p)
           nil)
    bootstrap-p))

(defn split-classpath-string
  [classpath-string]
  (string/split
    classpath-string
    ;; re-pattern should be safe given the characters that can be separators, but could be safer
    (re-pattern File/pathSeparator)))

(defn classpath-string->jar
  [classpath-string jar-location]
  (let [classpath
        (map io/file (split-classpath-string classpath-string))
        bootstrap-p (create-bootstrap)]
    (spit-jar!
      jar-location
      (concat ;; directories on the classpath
              (mapcat
                (fn [cp-dir]
                  (let [cp-dir-p (.toPath cp-dir)]
                    (map
                      (juxt #(str (.relativize cp-dir-p (.toPath %)))
                            identity)
                      (filter (memfn isFile) (file-seq cp-dir)))))
                (filter (memfn isDirectory) classpath))
              ;; jar deps
              (sequence
                (comp
                  (map file-seq)
                  cat
                  (filter (memfn isFile))
                  (filter #(by-ext % "jar"))
                  (map (juxt (comp
                               elodin/path-seq->str
                               #(cons "lib" %)
                               elodin/hash-derived-name)
                             identity)))
                classpath)
              ;; resources (LICENSE and version) from onejar
              #_(comment (into []
                               (comp (filter (memfn isFile))
                                     (map #(string/replace % #"^src/" ""))
                                     (map (juxt #(string/replace % #"^.*resources/" "")
                                                (fn [x]
                                                  (list 'io/resource x)))))
                               (file-seq (io/file "src/mach/pack/alpha/bootstrap/onejar/resources/"))))
              [[".version" (io/resource "mach/pack/alpha/bootstrap/onejar/resources/.version")]
               ["doc/one-jar-license.txt" (io/resource "mach/pack/alpha/bootstrap/onejar/resources/doc/one-jar-license.txt")]]
              ;; compiled bootstrap files
              (sequence
                (comp
                  (filter (memfn isFile))
                  (filter #(by-ext % "class"))
                  (map (juxt #(str (.relativize bootstrap-p (.toPath %))) identity)))
                (file-seq (.toFile bootstrap-p))))
      [["One-Jar-Main-Class" "clojure.main"]
       ;; See https://dev.clojure.org/jira/browse/CLJ-971
       ["One-Jar-URL-Factory" "com.simontuffs.onejar.JarClassLoader$OneJarURLFactory"]]
      "com.simontuffs.onejar.Boot")))

(defn paths-get
  [[first & more]]
  (Paths/get first (into-array String more)))

(def ^:private cli-options
  [["-e" "--extra-path STRING" "add directory to classpath for building"
    :assoc-fn (fn [m k v] (update m k conj v))]
   ["-d" "--deps STRING" "deps.edn file location"
    :default "deps.edn"
    :validate [(comp (memfn exists) io/file) "deps.edn file must exist"]]
   ["-h" "--help" "show this help"]])

(defn- usage
  [summary]
  (->>
    ["Usage: clj -m mach.pack.alpha.one-jar [options] <path/to/output.jar>"
     ""
     "Options:"
     summary
     ""
     "output.jar is where to put the output uberjar. Leading directories will be created."]
    (string/join \newline)))

(defn- error-msg [errors]
  (str "The following errors occurred while parsing your command:\n\n"
       (string/join \newline errors)))

(defn -main
  [& args]
  (let [{{:keys [deps
                 extra-path
                 help]} :options
         [output] :arguments
         :as parsed-opts}
        (cli/parse-opts args cli-options)
        errors (cond-> (:errors parsed-opts)
                 (not output)
                 (conj "Output jar must be specified"))]
    (cond
      help
      (println (usage (:summary parsed-opts)))
      errors
      (println (error-msg errors))
      :else
      (let [deps-map (tools.deps.reader/slurp-deps (io/file deps))]
        (classpath-string->jar
          (tools.deps/make-classpath
            (tools.deps/resolve-deps deps-map nil)
            (map
              #(.resolveSibling (paths-get [deps])
                                (paths-get [%]))
              (:paths deps-map))
            {:extra-paths extra-path})
          output)))))

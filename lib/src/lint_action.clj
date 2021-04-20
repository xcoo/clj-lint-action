(ns lint-action
  (:require [clojure.string :as cstr]
            [clojure.edn :as edn]
            [clojure.java.shell :refer [sh]]))

(def check-name "clj-lint action")

(def eastwood-linters [:bad-arglists :constant-test :def-in-def :deprecations
                       :keyword-typos :local-shadows-var :misplaced-docstrings
                       :no-ns-form-found :redefd-vars
                       :suspicious-expression :suspicious-test :unlimited-use
                       :unused-fn-args :unused-locals :unused-meta-on-macro
                       :unused-namespaces :unused-private-vars :unused-ret-vals
                       :unused-ret-vals-in-try :wrong-arity :wrong-ns-form
                       :wrong-pre-post :wrong-tag])

(defn- get-files [dir]
  (let [files (sh "find" dir "-name" "*.clj" "-printf" "%P\n")]
    (when (zero? (:exit files))
      (cstr/split-lines (:out files)))))

(defn- get-diff-files [dir git-sha]
  (let [commit-count (->> (sh "sh"
                              "-c"
                              (str "cd "
                                   dir
                                   ";"
                                   "git log  --oneline --no-merges | wc -l"))
                          :out
                          cstr/split-lines
                          first
                          Integer/valueOf)]
    (if (< commit-count 2)
      (get-files dir)
      (->> (sh "sh" "-c" (str "cd " dir ";"
                              "git diff --name-only --relative " git-sha))
           :out
           cstr/split-lines
           (filter #(re-matches #"--- a(.*clj)$" %))))))

(defn- filename->namespace [filename]
  (let [splited-name (cstr/split (cstr/replace filename #".clj$" "")
                                 #"/")]
    (case (first splited-name)
      "src" (->> (next splited-name)
                 (map #(cstr/replace % #"_" "-"))
                 (cstr/join ".")
                 symbol)

      "test" (->> (next splited-name)
                  (map #(cstr/replace % #"_" "-"))
                  (cstr/join ".")
                  symbol)
      nil)))

(defn- join-path [& args]
  (->> (remove empty? args)
       (map #(cstr/split % #"/"))
       (apply concat)
       (cstr/join "/")))

(defn- run-clj-kondo [dir relative-files relative-dir]
  (let [kondo-result
        (sh "sh" "-c"
            (str "cd " dir ";"
                 "/usr/local/bin/clj-kondo " "--lint "
                 (cstr/join \: relative-files)))
        result-lines
        (when (or (= (:exit kondo-result) 2) (= (:exit kondo-result) 3))
          (cstr/split-lines (:out kondo-result)))]
    (->> result-lines
         (keep (fn [line]
                 (when-let
                  [matches (re-matches
                            #"^(.*?)\:(\d*?)\:(\d*?)\:([a-z ]*)\:(.*)"
                            line)]
                   {:path (str relative-dir "/" (second matches))
                    :start_line (Integer/valueOf (nth matches 2))
                    :end_line (Integer/valueOf (nth matches 2))
                    :annotation_level "warning"
                    :message
                    (str "[clj-kondo]" (nth matches 5))}))))))

(defn- run-cljfmt [files cwd relative-dir]
  (let [cljfmt-result (sh "sh"
                          "-c"
                          (str
                           "clojure -Sdeps \"{:deps {cljfmt "
                           "{:mvn/version \\\"RELEASE\\\" }}}\" "
                           " -m cljfmt.main check "
                           (cstr/join " " files)))]
    (when-not (zero? (:exit cljfmt-result))
      (->> (:err cljfmt-result)
           cstr/split-lines
           (filter #(re-matches #"--- a(.*clj)$" %))
           (map (fn [line]
                  (let [file (join-path relative-dir
                                        (subs line (count (str "--- a" cwd))))]
                    {:path file
                     :start_line 0
                     :end_line 0
                     :annotation_level "warning"
                     :message (str "[cljfmt] cljfmt fail." file)})))))))

(defn- run-eastwood-clj [dir namespaces linters]
  (sh "sh" "-c"
      (str
       "cd " dir ";"
       "clojure "
       "-Sdeps " "\" {:deps {jonase/eastwood "
       "{:mvn/version \\\"RELEASE\\\" }}}\" "
       " -m  " "eastwood.lint "
       (pr-str (pr-str {:source-paths ["src"]
                        :linters linters
                        :namespaces namespaces})))))

(defn- run-eastwood-lein [dir namespaces linters]
  (sh "sh" "-c"
      (str "cd " dir ";"
           "lein "
           " update-in :plugins conj \"[jonase/eastwood \\\"0.3.5\\\"]\" "
           "-- update-in :eastwood assoc :linters "  (pr-str (pr-str linters))
           " -- eastwood "
           (pr-str (pr-str {:namespaces (vec namespaces)})))))

(defn- run-eastwood [dir runner namespaces linters]
  (let [eastwood-result (if (= runner :leiningen)
                          (run-eastwood-lein dir namespaces linters)
                          (run-eastwood-clj dir namespaces linters))]
    (->> (cstr/split-lines (:out eastwood-result))
         (map (fn [line]
                (when-let [matches
                           (re-matches #"^(.*?)\:(\d*?)\:(\d*?)\:(.*?)\:(.*)"
                                       line)]
                  (let [message (str "[eastwood]"
                                     "[" (cstr/trim (nth matches 4)) "]"
                                     (nth matches 5))]
                    {:path (second matches)
                     :start_line (Integer/valueOf (nth matches 2))
                     :end_line (Integer/valueOf (nth matches 2))
                     :annotation_level "warning"
                     :message message}))))
         (filter identity))))

(defn- run-kibit [dir files relative-dir]
  (let [kibit-result (sh
                      "sh" "-c"
                      (str
                       "cd " dir ";"
                       "clojure "
                       "-Sdeps "
                       "\" {:deps {tvaughan/kibit-runner "
                       "{:mvn/version \\\"RELEASE\\\" }}}\" "
                       " -m  " "kibit-runner.cmdline " (cstr/join " " files)))]
    (->> (cstr/split (:out kibit-result) #"\n\n")
         (map (fn [line]
                (let [message-lines (cstr/split-lines line)
                      first-line (first message-lines)
                      message (cstr/join "\n" (next message-lines))]
                  (when-let [line-decompose
                             (re-matches #"At (.*?):(\d*?):$" first-line)]
                    {:path (join-path relative-dir (second line-decompose))
                     :start_line (Integer/valueOf (nth line-decompose 2))
                     :annotation_level "warning"
                     :end_line (Integer/valueOf (nth line-decompose 2))
                     :message (str "[kibit]\n" message)}))))
         (filter identity))))

(def default-option {:linters "all"
                     :cwd "./"
                     :relative-dir ""
                     :mode :cli
                     :file-target :find
                     :use-files false
                     :files []
                     :max-annotation 50
                     :git-sha "HEAD~"
                     :eastwood-linters eastwood-linters
                     :runner :clojure})

(defn- fix-option [option]
  (->> option
       (map (fn [[k v]]
              (cond
                (= [k v] [:linters "all"])
                [:linters ["eastwood" "cljfmt" "kibit" "clj-kondo"]]
                :else [k v])))
       (into {})))

(defn- run-linters [{:keys [linters cwd relative-dir file-target runner
                            git-sha use-files files eastwood-linters]}]
  (when-not (coll? linters) (throw (ex-info "Invalid linters." {})))
  (let [dir (join-path cwd relative-dir)
        relative-files (cond
                         use-files (filter #(re-find #".clj$" %) files)
                         (= file-target :git) (get-diff-files dir git-sha)
                         :else (get-files dir))
        absolute-files (map #(join-path dir %) relative-files)
        dir' (str dir "/")
        relative-dir (if (empty? relative-dir) "." relative-dir)
        namespaces (->> relative-files
                        (map filename->namespace)
                        (filter identity))]
    (when (seq relative-files)
      (mapcat #(case %
                 "eastwood"
                 (run-eastwood dir runner namespaces eastwood-linters)
                 "kibit" (run-kibit dir relative-files relative-dir)
                 "cljfmt" (run-cljfmt absolute-files dir' relative-dir)
                 "clj-kondo" (run-clj-kondo dir relative-files relative-dir))
              linters))))

(defn- external-run [option]
  (run-linters  option))

(defn- convert-message-for-workflow [message]
  (cstr/replace message #"\n" " "))

(defn- print-workflow-warning [lint-result]
  (doseq [annotation lint-result]
    (println (format "::warning file=%s,line=%d,col=1::%s"
                     (:path annotation)
                     (:start-line annotation)
                     (convert-message-for-workflow (:message annotation))))))

(defn- output-lint-result [lint-result]
  (doseq [annotation lint-result]
    (println (format "%s:%d" (:path annotation) (:start_line annotation)))
    (println (:message annotation))
    (println "")))

(defn -main
  ([] (-main (pr-str default-option)))
  ([arg-string]
   (let [option (->> (edn/read-string arg-string)
                     (merge default-option)
                     fix-option)
         lint-result (external-run option)]
     (if (= (:mode option) :github-action)
       (do (print-workflow-warning (take (:max-annotation option) lint-result))
           (when (seq lint-result) (System/exit 1)))
       (output-lint-result lint-result)))))

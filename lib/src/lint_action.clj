(ns lint-action
  (:require [cheshire.core :as cheshire]
            [environ.core :refer [env]]
            [clj-http.client :as client]
            [clj-time.core :as clj-time]
            [clojure.string :as cstr]
            [clojure.edn :as edn]
            [clojure.java.shell :refer [sh]]))

(def check-name "clj-lint action")

(def eastwood-linters [:bad-arglists :constant-test :def-in-def :deprecations
                       :keyword-typos :local-shadows-var :misplaced-docstrings :no-ns-form-found :redefd-vars
                       :suspicious-expression :suspicious-test :unlimited-use
                       :unused-fn-args :unused-locals :unused-meta-on-macro
                       :unused-namespaces :unused-private-vars :unused-ret-vals
                       :unused-ret-vals-in-try :wrong-arity :wrong-ns-form
                       :wrong-pre-post :wrong-tag])

(defn- make-header []
  {"Content-Type" "application/json"
   "Accept" "application/vnd.github.antiope-preview+json"
   "Authorization" (str "Bearer " (env :input-github-token))
   "User-Agent" "clj-lint"})

(defn- start-action []
  (let [post-result (client/post (str "https://api.github.com/repos/"
                                      (env :github-repository)
                                      "/check-runs")
                                 {:headers (make-header)
                                  :content-type :json
                                  :body
                                  (cheshire/generate-string
                                   {:name check-name
                                    :head_sha (env :github-sha)
                                    :status "in_progress"
                                    :started_at (str (clj-time/now))})})]
    (get (cheshire/parse-string (:body post-result)) "id")))

(defn- update-action [id conclusion output max-annotation]
  (client/patch
   (str "https://api.github.com/repos/"
        (env :github-repository)
        "/check-runs/"
        id)
   {:headers (make-header)
    :content-type :json
    :body
    (cheshire/generate-string
     {:name check-name
      :head_sha (env :github-sha)
      :status "completed"
      :completed_at (str (clj-time/now))
      :conclusion conclusion
      :output
      {:title check-name
       :summary "Results of linters."
       :annotations (take max-annotation output)}})}))

(defn- get-files [dir]
  (let [files (sh "find" dir "-name" "*.clj" "-printf" "%P\n")]
    (when (zero? (:exit files))
      (cstr/split-lines (:out files)))))

(defn- get-diff-files [dir git-sha]
  (let [commit-count (->> (sh "sh" "-c" (str "cd " dir ";"
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

(defn- run-clj-kondo [dir files relative-dir]
  (let [kondo-result (apply sh (concat ["/usr/local/bin/clj-kondo" "--lint"]  files))
        result-lines
        (when (or (= (:exit kondo-result) 2) (= (:exit kondo-result) 3))
          (cstr/split-lines (:out kondo-result)))]
    (->> result-lines
         (map (fn [line]
                (when-let [matches (re-matches #"^(.*?)\:(\d*?)\:(\d*?)\:([a-z ]*)\:(.*)" line)]
                  {:path (str relative-dir "/" (subs (second matches) (count dir)))
                   :start_line (Integer/valueOf (nth matches 2))
                   :end_line (Integer/valueOf (nth matches 2))
                   :annotation_level "warning"
                   :message
                   (str "[clj-kondo]" (nth matches 5))})))
         (filter identity))))

(defn- run-cljfmt [files cwd relative-dir]
  (let [cljfmt-result (sh "sh"
                          "-c"
                          (str
                           "clojure -Sdeps \"{:deps {cljfmt {:mvn/version \\\"RELEASE\\\" }}}\" -m cljfmt.main check "
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

(defn- run-eastwood-clj [dir namespaces]
  (sh "sh" "-c"
      (str
       "cd " dir ";"
       "clojure "
       "-Sdeps " "\" {:deps {jonase/eastwood {:mvn/version \\\"RELEASE\\\" }}}\" "
       " -m  " "eastwood.lint "
       (pr-str (pr-str {:source-paths ["src"]
                        :linters eastwood-linters
                        :namespaces namespaces})))))

(defn- run-eastwood-lein [dir namespaces]
  (sh "sh" "-c"
      (str "cd " dir ";"
           "lein "
           " update-in :plugins conj \"[jonase/eastwood \\\"0.3.5\\\"]\" "
           "-- update-in :eastwood assoc :add-linters "  (pr-str (pr-str eastwood-linters))
           " -- eastwood "
           (pr-str (pr-str {:namespaces (vec namespaces)})))))

(defn- run-eastwood [dir runner namespaces]
  (let [eastwood-result (if (= runner :leiningen) (run-eastwood-lein dir namespaces) (run-eastwood-clj dir namespaces))]
    (->> (cstr/split-lines (:out eastwood-result))
         (map (fn [line]
                (when-let [matches (re-matches #"^(.*?)\:(\d*?)\:(\d*?)\:(.*?)\:(.*)" line)]
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
                       "-Sdeps " "\" {:deps {tvaughan/kibit-runner {:mvn/version \\\"RELEASE\\\" }}}\" "
                       " -m  " "kibit-runner.cmdline " (cstr/join " " files)))]
    (->> (cstr/split (:out kibit-result) #"\n\n")
         (map (fn [line]
                (let [message-lines (cstr/split-lines line)
                      first-line (first message-lines)
                      message (cstr/join "\n" (next message-lines))]
                  (when-let [line-decompose (re-matches #"At (.*?):(\d*?):$" first-line)]
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
                     :runner :clojure})

(defn- fix-option [option]
  (->> option
       (map (fn [[k v]]
              (cond
                (= [k v] [:linters "all"]) [:linters ["eastwood" "cljfmt" "kibit" "clj-kondo"]]
                :else [k v])))
       (into {})))

(defn- run-linters [{:keys [linters cwd relative-dir file-target runner git-sha use-files files]}]
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
       (->> linters
            (map #(case %
                    "eastwood" (run-eastwood dir runner namespaces)
                    "kibit" (run-kibit dir relative-files relative-dir)
                    "cljfmt" (run-cljfmt absolute-files dir' relative-dir)
                    "clj-kondo" (run-clj-kondo dir' absolute-files relative-dir)))
            (apply concat)))))

(defn- external-run [option]
  (run-linters  option))

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
         id (when (= (:mode option) :github-action) (start-action))
         lint-result (external-run option)
         conclusion (if (empty? lint-result) "success" "neutral")]
     (if (= (:mode option) :github-action)
       (update-action id  conclusion lint-result (:max-annotation option))
       (output-lint-result lint-result)))))

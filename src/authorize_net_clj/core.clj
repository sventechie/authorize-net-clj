(ns authorize-net-clj.core
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]))

;; borrowed from sveri/clojure-commons
(defn from-edn [fname]
  "reads an edn file from classpath"
  (with-open [rdr (-> (io/resource fname)
                       io/reader
                       java.io.PushbackReader.)]
    (edn/read rdr)))

(def config (from-edn "authorize-net-config.edn"))
(defn http-bool [bool]
  "Convert boolean to HTTP format"
  (clojure.string/upper-case (str bool)))

;; Read config settings
(def api-login (-> config :api-login))
(def api-key (-> config :api-key))
(def email-receipt? (-> config :email-receipt http-bool))
(def market-type (-> config :market-type))
(def processing-endpoint (-> config :processing-endpoint))
(def test-mode? (-> config :test-mode))
(def default-description (-> config :default-description))

(def xtype #{"AUTH_CAPTURE" "AUTH_ONLY" "PRIOR_AUTH_CAPTURE" "CAPTURE_ONLY" "CREDIT" "VOID"})

(def response-codes
  ;; Approved/Declined/Error/Held for Review
  {1 "This transaction has been approved."
   2 "This transaction has been declined."
   3 "There has been an error processing this transaction."
   4 "This transaction is being held for review."})

(ns authorize-net-clj.xml
;; inspired by Seth Buntin https://github.com/sethtrain
  (:gen-class)
  (:require [clj-http.client :as client]
            [clojure.data.xml :as xml]
            [clojure.java.io :as io]
            [authorize-net-clj.core :as common]
            [net.cgrand.enlive-html :as xml-tpl :refer [deftemplate defsnippet content substitute clone-for]]
            [plumbing.core :as p :only [defnk]]))

;; default to XML parser
(xml-tpl/set-ns-parser! xml-tpl/xml-parser)


(defn http-bool [bool]
  (clojure.string/upper-case (str bool)))

;; Read config settings
(def api-login (-> common/config :api-login))
(def api-key (-> common/config :api-key))
(def email-receipt? (-> common/config :email-receipt http-bool))
(def market-type (-> common/config :market-type))
(def processing-endpoint (-> common/config :processing-endpoint))
(def test-mode? (-> common/config :test-mode))
(def default-description (-> common/config :default-description))

(def endpoints
  "Authorize.Net's API target URLs"
  { :test "https://apitest.authorize.net/xml/v1/request.api"
    :production "https://api.authorize.net/xml/v1/request.api" })

; These macros are from David Nolen's Enlive tutorial
; https://github.com/swannodette/enlive-tutorial/

;; replace value if not nil
(defmacro maybe-substitute
  ([expr] `(if-let [x# ~expr] (xml-tpl/substitute x#) identity))
  ([expr & exprs] `(maybe-substitute (or ~expr ~@exprs))))

;; insert content if not nil
(defmacro maybe-content
  ([expr] `(if-let [x# ~expr] (xml-tpl/content x#) identity))
  ([expr & exprs] `(maybe-content (or ~expr ~@exprs))))

;; Default query map
(def ^:dynamic *default-query*
   {:createTransactionRequest
    {:merchantAuthentication
      {:name api-login
       :transactionKey api-key }
     :transactionRequest
       {:transactionSettings
        [{:setting
          {:settingName "testRequest"
           :settingValue test-mode? }}]}}})

(defn remove-namespaces
  "Remove namespace specs to allow broken parser to work"
  [xml-string]
  (clojure.string/replace xml-string #"\sxmlns(:.+)?=\".+\"" ""))

(defn transaction-template-old
  "Create transaction template from XML file"
  []
  (xml/parse-str
    (slurp "resources/templates/authorize-net-transaction.xml")))

(defsnippet card-snippet "templates/authorize-net-transaction.xml"
  [:payment [:creditCard]]
    [{:keys [card-number exp-date card-code]}]
      [:creditCard [:cardNumber]] (xml-tpl/content (str card-number))
      [:creditCard [:expirationDate]] (xml-tpl/content (str exp-date))
      [:creditCard [:cardCode]] (xml-tpl/content (str card-code)))

(defsnippet test-mode-snippet "templates/authorize-net-transaction.xml"
  [:transactionSettings [:setting]]
    [{:keys [test-mode]}]
      [:setting [:settingName]] (xml-tpl/content "testRequest")
      [:setting [:settingValue]] (xml-tpl/content (str test-mode)))

(defsnippet line-item-snippet "templates/authorize-net-transaction.xml"
  [:lineItems [:lineItem xml-tpl/first-of-type]] [line-items]
    [:lineItem] (clone-for [item line-items]
      [:itemId] (xml-tpl/content (str (:id item)))
      [:name] (xml-tpl/content (str (:name item)))
      [:description] (xml-tpl/content (str (:description item)))
      [:quantity] (xml-tpl/content (str (:quantity item)))
      [:unitPrice] (xml-tpl/content (str (:unit-price item)))))

(defsnippet user-field-snippet "templates/authorize-net-transaction.xml"
  [:userFields [:userField xml-tpl/first-of-type]] [user-fields]
    [:userField] (clone-for [field user-fields]
      [:name] (xml-tpl/content (str (:name field)))
      [:value] (xml-tpl/content (str (:value field)))))

;; payment
(deftemplate payment-template "templates/authorize-net-transaction.xml"
  [{:keys [transaction authentication credit-card user-fields]}]
    [:merchantAuthentication [:name]] (xml-tpl/content (:api-login authentication))
    [:merchantAuthentication [:transactionKey]] (xml-tpl/content (:api-key authentication))
    [:refId] (xml-tpl/content (:id transaction))
    [:transactionType] (xml-tpl/content "authCaptureTransaction")
    [:amount] (xml-tpl/content (:amount transaction))
    [:payment [:trackData]] nil ;; delete, card present only
    [:payment] (xml-tpl/content (card-snippet credit-card))
    [:order [:invoiceNumber]] (xml-tpl/content (:invoice-num transaction))
    [:order [:description]] (xml-tpl/content (:description transaction))
    [:lineItems] (xml-tpl/content (line-item-snippet (:line-items transaction)))
    [:poNumber] (xml-tpl/content (:purchase-order transaction))
    [:tax [:amount]] (xml-tpl/content (-> transaction :tax :amount))
    [:tax [:name]] (xml-tpl/content (-> transaction :tax :name))
    [:tax [:description]] (xml-tpl/content (-> transaction :tax :description))
    [:duty] (xml-tpl/content "")
    [:shipping] (xml-tpl/content "")
    [:customer [:id]] (xml-tpl/content (:customer-id transaction))
    [:retail] nil ;; delete retail, card present only
    [:transactionSettings] (xml-tpl/content (test-mode-snippet {:test-mode test-mode?}))
    [:userFields] (xml-tpl/content (user-field-snippet user-fields)))


(defn format-card-transaction
  "Format transaction data for submission to the API"
  [transaction-map]
  (clojure.string/join
    (payment-template
      {:transaction
        {:id "44332211"
         :invoice-num "INV-54321"
         :description "Online Order"
         :purchase-order "456654"
         :customer-id "99999456654"
         :amount "5.67"
         :line-items (:line-items transaction-map)}
       :authentication
         {:api-login (:api-login transaction-map)
          :api-key (:api-key transaction-map)}
       :credit-card
         {:card-number "5424000000000015"
          :exp-date "1220"
          :card-code "999"}
       :user-fields
         [{:name "favorite_color" :value "blue"}
          {:name "favorite_number" :value 42}]})))

;; Request
(defn do-request [url default-query trans-info]
  "Executes http client"
  (client/post url {:body (format-card-transaction (merge default-query trans-info))
                    :content-type :xml}))

(defn response-template
  "Create response template from XML file"
  []
  (xml/parse-str
   (remove-namespaces
    (slurp "resources/templates/authorize-net-response-example.xml"))))

(def response-codes
  ;; Approved/Declined/Error/Held for Review
  {1 "This transaction has been approved."
   2 "This transaction has been declined."
   3 "There has been an error processing this transaction."
   4 "This transaction is being held for review."})

;; Authorize.net AIM response fields (41-50 are empty)
(def response-fields
        [:response_code :response_subcode
         :reason_code :reason_text
         :auth_text :avs_response
         :transaction_id :invoice_number :description
         :amount :method :transaction_type
         :customer_id :first_name :last_name
         :company :address :city
         :state :zip_code :country
         :phone :fax :email
         :ship_first_name
         :ship_last_name
         :ship_company
         :ship_address
         :ship_city
         :ship_state
         :ship_zip_code
         :ship_country
         :tax :duty :freight
         :tax_exempt :purchase_order_number
         :md5_hash :card_code_response :cavv_response
         :col_41 :col_42 :col_43 :col_44 :col_45 :col_46 :col_47 :col_48 :col_49 :col_50
         :account_number :card_type
         :split_tender_id :requested_amount :balance_on_card
         :col_56 :col_57 :col_58 :col_59 :col_60 :col_61 :col_62 :col_63 :col_64 :col_65
         :col_66 :col_67 :col_68
         ; add merchant-defined fields here :merch_field_1 :merch_field_2
         :special_instructions
         :SpecialCode])

(defn xml-parse
  "Get map of XML response"
  [response]
  (xml/parse-str response true))

(defn- api-response [response-map]
  (cond (some? (:auth_text response-map))
          (response-map)
        :else
        {:code nil,
         :reason_code "0"
         :reason_text "API Failure"}))


(defn process-payment [trans-info]
  "Basic method to handle api request and response"
  (api-response
   (xml-parse
    (do-request (get endpoints processing-endpoint)
                *default-query* trans-info))))

(defn void-transaction [trans-info]
  (api-response
   (xml-parse
    (do-request (get endpoints processing-endpoint)
                *default-query*
                (conj {:x_type "VOID"} trans-info)))))

(defn echeck-payment [trans-info]
  (api-response
   (xml-parse
    (do-request (get endpoints processing-endpoint)
                *default-query*
                (conj {:x_method "ECHECK"
                       :x_recurring_billing "FALSE"}
                      trans-info)))))

(defn raw-transaction
  "Basic transaction without wrapping"
  [trans-info]
  (do-request (get endpoints processing-endpoint)
     *default-query* trans-info))

(defn show-transaction
  "Basic transaction without wrapping"
  [trans-info]
  (clojure.pprint/pprint (conj *default-query* trans-info)))


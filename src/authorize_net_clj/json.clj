(ns authorize-net-clj.json
;; inspired by Seth Buntin https://github.com/sethtrain
  (:gen-class)
  (:require [clj-http.client :as client]
            [cheshire.core :refer :all]
            [clojure.java.io :as io]
            [authorize-net-clj.core :as common]
            [plumbing.core :as p]))

;; data formatting / type functions

(defn http-bool [bool]
  "Convert boolean to HTTP format"
  (clojure.string/upper-case (str bool)))

(defn http-type
  "Coerce to appropriate string format for HTTP"
  [item]
  (cond
   (= java.lang.Boolean (type item)) (http-bool item)
   :else (str item)))

(defn list-type
  "Format a list in Authorize.Net CSV"
  [items]
  (clojure.string/join "<|>" (map #(http-type %) items)))

(p/defnk format-line-item
  "Format a line item according to Authorize.Net specs"
  [id name quantity price {description ""} {taxable false}]
  (let [item [id name description quantity price taxable]]
    (list-type item)))

(defn cost-detail
  "Format a cost detail, e.g., tax/freight/duty in Authorize.Net CSV"
  [{:keys [name description price]}]
  (let [item [name description price]]
    (list-type item)))

(defn cost-type
  "Format a cost map or number"
  [cost-item]
  (cond
    (map? cost-item) (cost-detail cost-item)
    :else (str cost-item)))

;; planed for inclusion in Clojure 1.7
(defn deep-merge
  "Like merge, but merges maps recursively."
  [& maps]
  (if (every? map? maps)
    (apply merge-with deep-merge maps)
    (last maps)))

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

;; Default query map
(def ^:dynamic *default-query*
  (sorted-map
   :createTransactionRequest
    {:merchantAuthentication {:name api-login :transactionKey api-key }
     :transactionRequest
      {:transactionSettings
        {:setting {:settingName "testRequest"
                   :settingValue test-mode? }}}}))

(defn transaction-template
  "Create transaction template from JSON file"
  []
  (parse-string
    (slurp "resources/templates/authorize-net-transaction.json") true))

;; Request
(defn do-request [url default-query trans-info]
  "Executes http client"
  (client/post url
            {:body (generate-string
                     (deep-merge trans-info default-query))
             :content-type :json}))

(defn response-template
  "Create response template from JSON file"
  []
  (parse-string
    (slurp "resources/templates/authorize-net-response-example.json") true))

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
         :account_number :card_type
         :split_tender_id :requested_amount :balance_on_card
         ; add merchant-defined fields here :merch_field_1 :merch_field_2
         :special_instructions
         :SpecialCode])

(defn parse-response
  "Get map of JSON response"
  [response]
  (parse-string response true))

(defn api-response [response-map]
  (cond (some? (:auth_text response-map))
          (response-map)
        :else
        {:code "911",
         :reason_code "911"
         :reason_text "API Failure"}))

(def billing-address
  { :cust_id "1234"
    :first_name "John"
    :last_name "Doe"
    :address "1234 Street"
    :city "Bellevue"
    :state "WA"
    :zip "98004"
    :country "US"
    :phone "123-456-7890"
    :email "sven.pedersen@gmail.com" })

(def shipping-address
  { :first_name "John"
    :last_name "Doe"
    :address "1234 Street"
    :city "Bellevue"
    :state "WA"
    :zip "98004"
    :country "US" })

(def example-items
  [{:id "Item1"
    :name "green bauble"
    :description "description1"
    :quantity 1
    :price "4.00"}
   {:id "Item2"
    :name "blue bauble"
    :description "description2"
    :quantity 1
    :price "15.00"
    :taxable true}])

(def example-transaction-map
  {:invoice_num "1234"
   :customer_ip "8.8.8.8"
   :description "Purchase from Crossroads Music Store"
   :amount 19.99
   :tax 3.21
   :freight {:name "Shipping" :description "Ground Overnight" :price 5.95}
   :line_items example-items
   :card_num "4111111111111111"
   :card_code "123"
   :exp_date "0415"
   :billing_address billing-address
   :shipping_address shipping-address})

(def default-shipping
  { :first_name ""
    :last_name ""
    :address ""
    :city ""
    :state ""
    :zip ""
    :country "" })

(p/defnk card-transaction
  "Format a transaction for Auth.net"
  [amount card_num card_code exp_date billing_address
   {invoice_num ""} {customer_ip "255.255.255.255"}
   {description default-description}
   {tax 0} {freight 0}
   {line_items []} {shipping_address default-shipping}
   {company ""} {special_instructions ""} {special_code ""}]
  {:x_type "AUTH_CAPTURE"
   :x_method "CC"

   ;; TRANS INFO
   :x_invoice_num invoice_num
   :x_customer_ip customer_ip
   :x_description description
   :x_amount (cost-type amount)
   :x_tax (cost-type tax)
   :x_freight (cost-type freight)
   :x_line_item (map format-line-item line_items)
   :x_email_customer email-receipt?

   :x_card_num card_num
   :x_card_code card_code
   :x_exp_date exp_date

   ;; CUSTOMER INFO
   :x_cust_id (:cust_id billing_address)
   :x_first_name (:first_name billing_address)
   :x_last_name (:last_name billing_address)
   :x_company (:company billing_address)
   :x_address (:address billing_address)
   :x_city (:city billing_address)
   :x_state (:state billing_address)
   :x_zip (:zip billing_address)
   :x_country (:country billing_address)
   :x_phone (:phone billing_address)
   :x_email (:email billing_address)

   ;; SHIPPING AND TAX
   :x_ship_to_first_name (:first_name shipping_address)
   :x_ship_to_last_name (:last_name shipping_address)
   :x_ship_to_address (:address shipping_address)
   :x_ship_to_city (:city shipping_address)
   :x_ship_to_state (:state shipping_address)
   :x_ship_to_zip (:zip shipping_address)
   :x_ship_to_country (:country shipping_address)

   ;; CUSTOM FIELDS
   :specialInstructions special_instructions
   :SpecialCode special_code
   })

(defn process-payment [trans-info]
  "Basic method to handle API request and response"
  (api-response
   (parse-response
    (do-request (get endpoints processing-endpoint)
                *default-query*
                trans-info))))

(defn void-transaction [trans-info]
  "Basic method to handle API void and response"
  (api-response
   (parse-response
    (do-request (get endpoints processing-endpoint)
                *default-query*
                (conj {:x_type "VOID"} trans-info)))))

(defn echeck-payment [trans-info]
  "Basic method to handle API void and response"
  (api-response
   (parse-response
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
  (generate-string
    (deep-merge trans-info *default-query*)))

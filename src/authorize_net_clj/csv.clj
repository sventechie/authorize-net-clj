(ns authorize-net-clj.csv
;; inspired by Seth Buntin https://github.com/sethtrain
  (:gen-class)
  (:require [clj-http.client :as client]
            [clojure.data.csv :as csv]
            [authorize-net-clj.core :as common]
            [plumbing.core :as p :only [defnk]]))

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
  { :test "https://test.authorize.net/gateway/transact.dll"
    :debug "https://developer.authorize.net/tools/paramdump/index.php"
    :production "https://secure.authorize.net/gateway/transact.dll" })

;; Default query map
(def ^:dynamic *default-query*
  {:x_delim_data "TRUE"
   :x_delim_char ","
   :x_version "3.1"
   :x_market_type market-type
   :x_relay_response "FALSE" ;; not supported by AIM
   :x_test_request (http-bool test-mode?)
   :x_allow_partial_Auth "FALSE"
   :x_login api-login
   :x_tran_key api-key})

;; Request
(defn do-request [url default-query trans-info]
  "Executes HTTP client"
  (client/post url {:form-params (conj default-query trans-info)}))

(def transaction-types
  #{ "VOID" "AUTH_CAPTURE" "PRIOR_AUTH_CAPTURE" "CAPTURE_ONLY" "CREDIT" })

;; Authorize.net AIM response fields (41-50 and 56-68 are empty)
(def response-fields
  [:response_code :response_subcode :reason_code :reason_text
   :auth_text :avs_response
   :transaction_id :invoice_number :description
   :amount :method :transaction_type
   :customer_id :first_name :last_name :company
   :address :city :state :zip_code :country
   :phone :fax :email
   :ship_first_name :ship_last_name :ship_company
   :ship_address :ship_city :ship_state :ship_zip_code :ship_country
   :tax :duty :freight
   :tax_exempt :purchase_order_number
   :md5_hash :card_code_response :cavv_response
   :col_41 :col_42 :col_43 :col_44 :col_45 :col_46 :col_47 :col_48 :col_49 :col_50
   :account_number :card_type
   :split_tender_id :requested_amount :balance_on_card
   :col_56 :col_57 :col_58 :col_59 :col_60 :col_61 :col_62 :col_63 :col_64 :col_65 :col_66 :col_67 :col_68
   :special_instructions :SpecialCode
   ; add merchant-defined fields here :merch_field_1 :merch_field_2
   ])

(def empty-columns
  [:col_41 :col_42 :col_43 :col_44 :col_45 :col_46 :col_47 :col_48 :col_49 :col_50
   :col_56 :col_57 :col_58 :col_59 :col_60 :col_61 :col_62 :col_63 :col_64 :col_65 :col_66 :col_67 :col_68])

(defn parse-response
  "One line CSV file"
  [response]
  (zipmap response-fields
    (first (csv/read-csv (:body response)))))

(defn api-response
  "Get response safely (guaranteed to have a code)"
  [response-map]
  (cond (some? (:response_code response-map))
          ;; remove superfluous cruft
          (apply dissoc response-map empty-columns)
        :else
        {:response_code nil
         :reason_code "0"
         :reason_text "API Failure"}))

(def default-shipping
  "Shipping is not required for most transactions"
  { :first_name ""
    :last_name ""
    :address ""
    :city ""
    :state ""
    :zip ""
    :country "" })

(p/defnk format-card-transaction
  "Format a Credit Card transaction for Authorize.net"
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
   :x_header_email_receipt ""
   :x_footer_email_receipt ""

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
   :SpecialCode special_code})

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
  (clojure.pprint/pprint (conj *default-query* trans-info)))

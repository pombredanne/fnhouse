(ns fnhouse.middleware
  "Middleware for coercing and schema-validating requests and responses."
  (:use plumbing.core)
  (:require
   [schema.coerce :as coerce]
   [schema.core :as s]
   [schema.utils :as utils]
   [fnhouse.schemas :as schemas]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Public Schemas

(s/defschema RequestRelativeCoercionMatcher
  "A coerce/CoercionMatcher whose data coercion function also takes the request.  Useful
   for, e.g., client-relative presentation rules, expanding relative urls, etc."
  (s/=> (s/maybe (s/=> s/Any schemas/Request s/Any)) schemas/Schema))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Private

(s/defn coercing-walker
  "Take a context key, schema, custom matcher, and seq of normal matchers, produce a walker
   that returns the datum of throws an error for validation failure."
  [context :- s/Keyword
   schema
   custom-matcher :- RequestRelativeCoercionMatcher
   normal-matchers :- [coerce/CoercionMatcher]]
  (with-local-vars [request-ref ::missing] ;; used to pass request through to custom coercers.
    (let [walker (->> (cons
                       (fn [schema] (when-let [c (custom-matcher schema)] #(c @request-ref %)))
                       normal-matchers)
                      vec
                      coerce/first-matcher
                      (coerce/coercer schema))]
      (fn [request data]
        (let [res (with-bindings {request-ref request} (walker data))]
          (if-let [error (utils/error-val res)]
            (throw (ex-info
                    (format "Request: [%s]<BR>==> Error: [%s]<BR>==> Context: [%s]"
                            (pr-str (select-keys request [:uri :query-string :body]))
                            (pr-str error)
                            context)
                    {:type :schema-error
                     :error error}))
            res))))))

(defn request-walker
  "Given a custom input coercer ( (constantly nil) for none), compile a function for coercing
   and validating requests (uri-args, query-params, and body)."
  [input-coercer handler-info]
  (let [request-walkers (for-map [[k coercer] {:uri-args coerce/string-coercion-matcher
                                               :query-params coerce/string-coercion-matcher
                                               :body coerce/json-coercion-matcher}
                                  :let [schema (safe-get-in handler-info [:request k])]
                                  :when schema]
                          k
                          (coercing-walker k schema input-coercer [coercer]))]
    (fn [request]
      (reduce-kv
       (fn [request request-key walker]
         (update-in request [request-key] (partial walker request)))
       request
       request-walkers))))

(defn response-walker
  "Given a custom output coercer ( (constantly nil) for none), compile a function for coercing
   and validating response bodies."
  [output-coercer handler-info]
  (let [response-walkers (map-vals (fn [s] (coercing-walker :response s output-coercer nil))
                                   (safe-get handler-info :responses))]
    (fn [request response]
      (let [walker (safe-get response-walkers (response :status 200))]
        (update-in response [:body] (partial walker request))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Public

(s/defn coercion-middleware :- schemas/AnnotatedHandler
  "Coerce and validate inputs and outputs.  Use walkers to simultaneously coerce and validate
   inputs in a generous way (i.e., 1.0 in body will be cast to 1 and validate against a long
   schema), and outputs will be clientized to match the output schemas as specified by
   output-coercer."
  [{:keys [handler info]} :- schemas/AnnotatedHandler
   input-coercer :- RequestRelativeCoercionMatcher
   output-coercer :- RequestRelativeCoercionMatcher]
  (let [request-walker (request-walker input-coercer info)
        response-walker (response-walker output-coercer info)]
    {:info info
     :handler (fn [request]
                (let [walked-request (request-walker request)]
                  (->> walked-request
                       handler
                       (response-walker walked-request))))}))

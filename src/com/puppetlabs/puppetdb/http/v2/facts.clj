(ns com.puppetlabs.puppetdb.http.v2.facts
  (:require [com.puppetlabs.puppetdb.query.facts :as f]
            [com.puppetlabs.puppetdb.http.query :as http-q]
            [com.puppetlabs.http :as pl-http]
            [cheshire.core :as json])
  (:use [net.cgrand.moustache :only [app]]
        com.puppetlabs.middleware
        [com.puppetlabs.jdbc :only [with-transacted-connection get-result-count]]
        [com.puppetlabs.puppetdb.http :only [query-result-response add-headers]]))

(defn query-facts
  "Accepts a `query` and a `db` connection, and returns facts matching the
  query. If the query can't be parsed or is invalid, a 400 error will be
  returned, and a 500 if something else goes wrong."
  [query db]
  (try
    (with-transacted-connection db
      (let [{[sql & params] :results-query
             count-query :count-query} (-> query
                                           (json/parse-string true)
                                           (f/v2-query->sql nil))

             resp (pl-http/json-response*
                   (pl-http/streamed-response buffer
                      (with-transacted-connection db
                        (f/with-queried-facts sql nil params #(pl-http/stream-json % buffer)))))]

        (if count-query
          (add-headers resp {:count (get-result-count count-query)})
          resp)))

    (catch com.fasterxml.jackson.core.JsonParseException e
      (pl-http/error-response e))
    (catch IllegalArgumentException e
      (pl-http/error-response e))))

(def query-app
  (app
   [&]
   {:get (comp (fn [{:keys [params globals] :as request}]
                 (query-facts (params "query") (:scf-read-db globals)))
               http-q/restrict-query-to-active-nodes)}))

(defn build-facts-app
  [query-app]
  (app
    []
    (verify-accepts-json query-app)

    [fact value &]
    (comp query-app
          (partial http-q/restrict-fact-query-to-name fact)
          (partial http-q/restrict-fact-query-to-value value))

    [fact &]
    (comp query-app (partial http-q/restrict-fact-query-to-name fact))))

(def facts-app
  (build-facts-app
    (validate-query-params query-app {:optional ["query"]})))

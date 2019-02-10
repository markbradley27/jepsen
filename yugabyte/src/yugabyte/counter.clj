(ns yugabyte.counter
  (:require [clojure.tools.logging :refer [debug info warn]]
            [jepsen [client    :as client]
             [checker   :as checker]
             [generator :as gen]]
            [jepsen.checker.timeline :as timeline]
            [clojurewerkz.cassaforte [client :as cassandra]
             [query :refer :all]
             [policies :refer :all]
             [cql :as cql]]
            [yugabyte [client :as c]]))

(def table-name "counter")
(def keyspace "jepsen")

(c/defclient CQLCounterClient keyspace []
  (setup! [this test]
    (cql/create-table conn table-name
                      (if-not-exists)
                      (column-definitions {:id :int
                                           :count :counter
                                           :primary-key [:id]}))
    (cql/update conn table-name {:count (increment-by 0)}
                (where [[= :id 0]])))

  (invoke! [this test op]
    (c/with-errors op #{:read}
      (case (:f op)
        :add (do (cql/update-with-ks conn keyspace table-name
                                     {:count (increment-by (:value op))}
                                     (where [[= :id 0]]))
                 (assoc op :type :ok))

        :read (let [value (->> (cql/select-with-ks conn keyspace table-name
                                                        (where [[= :id 0]]))
                                    first
                                    :count)]
                     (assoc op :type :ok :value value)))))

  (teardown! [this test]))

(def add {:type :invoke :f :add :value 1})
(def sub {:type :invoke :f :add :value -1})
(def r   {:type :invoke :f :read})

(defn workload
  [opts]
  {:client          (->CQLCounterClient)
   :generator       (->> (repeat 100 add)
                          (cons r)
                          gen/mix
                          (gen/delay 1/10))
   :checker         (checker/compose
                      {:timeline (timeline/html)
                       :counter  (checker/counter)})})

(defn workload-dec
  [opts]
  (assoc (workload opts)
         :generator (->> (take 100 (cycle [add sub]))
                         (cons r)
                         gen/mix
                         (gen/delay 1/10))))

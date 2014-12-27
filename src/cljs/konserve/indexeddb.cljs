(ns konserve.indexeddb
  (:require [konserve.platform :refer [log read-string-safe]]
            [konserve.protocols :refer [IEDNAsyncKeyValueStore -get-in -assoc-in -update-in
                                        IJSONAsyncKeyValueStore -jget-in -jassoc-in -jupdate-in
                                        IBinaryAsyncKeyValueStore -bget -bassoc]]
            [cljs.core.async :as async :refer (take! <! >! put! close! chan)])
  (:require-macros [cljs.core.async.macros :refer [go go-loop]]))


;; port to transit or cljs.fressian for faster edn encoding
(defrecord IndexedDBKeyValueStore [db store-name tag-table]
  IEDNAsyncKeyValueStore
  (-get-in [this key-vec]
    (let [[fkey & rkey] key-vec
          res (chan)
          tx (.transaction db #js [store-name])
          obj-store (.objectStore tx store-name)
          req (.get obj-store (pr-str fkey))]
      (set! (.-onerror req)
            (partial error-handler (str "cannot get-in" fkey) res))
      (set! (.-onsuccess req)
            (fn [e] (when-let [r (.-result req)]
                     (put! res (get-in (->> (aget r "edn_value")
                                            (read-string-safe @tag-table))
                                       rkey)))
              ;; returns nil
              (close! res)))
      res))
  (-assoc-in [this key-vec value]
    (let [[fkey & rkey] key-vec
          res (chan)
          tx (.transaction db #js [store-name] "readwrite")
          obj-store (.objectStore tx store-name)
          req (.get obj-store (pr-str fkey))]
      (set! (.-onerror req)
            (partial error-handler (str "cannot get for assoc-in" fkey) res))
      (set! (.-onsuccess req)
            (fn read-old [e]
              (let [old (when-let [r (.-result req)]
                          (->> (aget r "edn_value") (read-string-safe @tag-table)))
                    up-req (if (or value (not (empty? rkey)))
                             (.put obj-store
                                   (clj->js {:key (pr-str fkey)
                                             :edn_value
                                             (pr-str (if-not (empty? rkey)
                                                       (assoc-in old rkey value)
                                                       value))}))
                             (.delete obj-store (pr-str fkey)))]
                (set! (.-onerror up-req)
                      (partial error-handler (str "cannot put for assoc-in" fkey) res))
                (set! (.-onsuccess up-req)
                      (fn [e] (close! res))))))
      res))
  (-update-in [this key-vec up-fn]
    (let [[fkey & rkey] key-vec
          res (chan)
          tx (.transaction db #js [store-name] "readwrite")
          obj-store (.objectStore tx store-name)
          req (.get obj-store (pr-str fkey))]
      (set! (.-onerror req)
            (partial error-handler (str "cannot get for update-in" fkey) res))
      (set! (.-onsuccess req)
            (fn read-old [e]
              (let [old (when-let [r (.-result req)]
                          (->> (aget r "edn_value")
                               (read-string-safe @tag-table)))
                    new (if-not (empty? rkey)
                          (update-in old rkey up-fn)
                          (up-fn old))
                    up-req (if new
                             (.put obj-store
                                   (clj->js {:key (pr-str fkey)
                                             :edn_value
                                             (pr-str new)}))
                             (.delete obj-store (pr-str fkey)))]
                (set! (.-onerror up-req)
                      (partial error-handler (str "cannot put for update-in" fkey) res))
                (set! (.-onsuccess up-req)
                      (fn [e]
                        (put! res [(get-in old rkey) (get-in new rkey)])
                        (close! res))))))
      res))

  IBinaryAsyncKeyValueStore
  (-bget [this key]
    (let [res (chan)
          tx (.transaction db #js [store-name])
          obj-store (.objectStore tx store-name)
          req (.get obj-store (pr-str key))]
      (set! (.-onerror req)
            (partial error-handler (str "cannot get-in" key) res))
      (set! (.-onsuccess req)
            (fn [e] (when-let [r (.-result req)]
                     (put! res (aget r "value")))
              ;; returns nil
              (close! res)))
      res))
  (-bassoc [this key blob]
    (let [res (chan)
          tx (.transaction db #js [store-name] "readwrite")
          obj-store (.objectStore tx store-name)
          req (.put obj-store #js {:key (pr-str key)
                                   :value blob})]
      (set! (.-onerror req)
            (partial error-handler (str "cannot put for assoc-in" fkey) res))
      (set! (.-onsuccess req)
            (fn [e] (close! res)))
      res))

  IJSONAsyncKeyValueStore
  (-jget-in [this key-vec]
    (let [[fkey & rkey] key-vec
          res (chan)
          tx (.transaction db #js [store-name])
          obj-store (.objectStore tx store-name)
          req (.get obj-store (pr-str fkey))]
      (set! (.-onerror req)
            (partial error-handler (str "cannot get-in" fkey) res))
      (set! (.-onsuccess req)
            (fn [e] (when-let [r (.-result req)]
                     (put! res (-> r
                                   (aget "json_value")
                                   js->clj
                                   (get-in rkey))))
              ;; returns nil
              (close! res)))
      res))
  (-jassoc-in [this key-vec value]
    (let [[fkey & rkey] key-vec
          res (chan)
          tx (.transaction db #js [store-name] "readwrite")
          obj-store (.objectStore tx store-name)
          req (.get obj-store (pr-str fkey))]
      (set! (.-onerror req)
            (partial error-handler (str "cannot get for assoc-in" fkey) res))
      (set! (.-onsuccess req)
            (fn read-old [e]
              (let [old (when-let [r (.-result req)]
                          (js->clj (-> r (aget "json_value"))))
                    up-req (if (or value (not (empty? rkey)))
                             (.put obj-store
                                   #js {:key (pr-str fkey)
                                        :json_value (if-not (empty? rkey)
                                                      (clj->js (assoc-in old rkey value))
                                                      value)})
                             (.delete obj-store (pr-str fkey)))]
                (set! (.-onerror up-req)
                      (partial error-handler (str "cannot put for assoc-in" fkey) res))
                (set! (.-onsuccess up-req)
                      (fn [e] (close! res))))))
      res))
  (-jupdate-in [this key-vec up-fn]
    (let [[fkey & rkey] key-vec
          res (chan)
          tx (.transaction db #js [store-name] "readwrite")
          obj-store (.objectStore tx store-name)
          req (.get obj-store (pr-str fkey))]
      (set! (.-onerror req)
            (partial error-handler (str "cannot get for update-in" fkey) res))
      (set! (.-onsuccess req)
            (fn read-old [e]
              (let [old (when-let [r (.-result req)]
                          (-> r (aget "json_value")))
                    new (if-not (empty? rkey)
                          (update-in old rkey up-fn)
                          (up-fn old))
                    up-req (if new
                             (.put obj-store
                                   (clj->js {:key (pr-str fkey)
                                             :json_value
                                             (pr-str new)}))
                             (.delete obj-store (pr-str fkey)))]
                (set! (.-onerror up-req)
                      (partial error-handler (str "cannot put for update-in" fkey) res))
                (set! (.-onsuccess up-req)
                      (fn [e]
                        (put! res [(get-in old rkey) (get-in new rkey)])
                        (close! res))))))
      res)))


(defn new-indexeddb-store
  "Create an IndexedDB backed edn store with tag-table if you need custom types/records,
e.g. {'namespace.Symbol (fn [val] ...)}

  Be careful not to mix up edn and JSON values."
  ([name] (new-indexeddb-store name (atom {})))
  ([name tag-table]
     (let [res (chan)
           req (.open js/window.indexedDB name 1)]
       (set! (.-onerror req)
             (partial error-handler "ERROR opening DB:" res))
       (set! (.-onsuccess req)
             (fn success-handler [e]
               (log "db-opened:" (.-result req))
               (put! res (IndexedDBKeyValueStore. (.-result req) name tag-table))))
       (set! (.-onupgradeneeded req)
             (fn upgrade-handler [e]
               (let [db (-> e .-target .-result)]
                 (.createObjectStore db name #js {:keyPath "key"}))
               (log "db upgraded from version: " (.-oldVersion e))))
       res)))



(comment
  ;; fire up repl
  (do
    (ns dev)
    (require 'weasel.repl.websocket)
    (cemerick.piggieback/cljs-repl
     :repl-env (weasel.repl.websocket/repl-env
                :ip "0.0.0.0" :port 9001)))


  (go (def my-store (<! (new-indexeddb-store "konserve"))))
  ;; or
  (-jassoc-in my-store ["test" "bar"] #js {:a 5})
  (go (println (<! (-jget-in my-store ["test"]))))
  (go (println "get:" (<! (-get-in my-store ["test" :a]))))

  (let [store my-store]
    (go (doseq [i (range 10)]
          (<! (-assoc-in store [i] i)))
        (doseq [i (range 10)]
          (println (<! (-get-in store [i]))))))

  (defrecord Test [a])
  (go (println (<! (-assoc-in my-store ["rec-test"] (Test. 5)))))
  (go (println (<! (-get-in my-store ["rec-test"]))))

  (go (println (<! (-assoc-in my-store ["test2"] {:a 1 :b 4.2}))))

  (go (println (<! (-assoc-in my-store ["test" :a] 43))))

  (go (println (<! (-update-in my-store ["test" :a] inc))))


  (go (println (<! (-bassoc my-store
                            "blob-fun"
                            (new js/Blob #js ["hello worlds"], #js {"type" "text/plain"})))))
  (go (.log js/console (<! (-bget my-store "blob-fun"))))
  )
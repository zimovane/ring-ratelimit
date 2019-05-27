(ns core.ratelimit
  (:import (java.util.concurrent TimeUnit)))

(def buckets (atom {}))

(defn wrap-ratelimit [handler & {:keys [interval interval-time-unit limit key-in-request whitelist fail-response]
                                 :or   {key-in-request [:remote-addr]
                                        whitelist      []
                                        fail-response  "Too Many Requests"}}]
  (fn [req respond raise]
    (let [bucket-key (get-in req key-in-request)]
      (if (.contains whitelist bucket-key)
        (handler req respond raise)
        (let [bucket-key        [bucket-key (get-in req [:headers "upgrade"] "http")]
              tokens-per-millis (/ limit (.toMillis interval-time-unit interval))
              bucket            (get-in @buckets bucket-key)
              now               (System/currentTimeMillis)
              last              (:timestamp bucket)
              remaining         (-> (if (some? bucket)
                                      (* (- now last)
                                         tokens-per-millis)
                                      limit)
                                    (+ (get bucket :remaining 0))
                                    (min limit))
              over-limit?       (< remaining 1)
              retry-after       (if over-limit?
                                  (-> (- 1 remaining)
                                      (/ tokens-per-millis 1000)
                                      Math/ceil
                                      long)
                                  0)
              rl-headers        {:x-ratelimit-limit     limit
                                 :x-ratelimit-remaining (int remaining)
                                 :x-ratelimit-reset     (+ (quot now 1000) retry-after)}]
          (if over-limit?
            (let [resp       {:status 429 :body fail-response}
                  rl-headers (assoc rl-headers :retry-after retry-after)]
              (->> #(merge % rl-headers)
                   (update resp :headers)
                   respond))
            (do
              (swap! buckets assoc-in bucket-key {:timestamp now :remaining (dec remaining)})
              (let [headers-fn #(merge % rl-headers)
                    respond-fn #(respond (update % :headers headers-fn))]
                (handler req respond-fn raise)))))))))
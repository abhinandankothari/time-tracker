(ns time-tracker.timers.db
  (:require [clojure.java.jdbc :as jdbc]
            [time-tracker.db :as db]
            [time-tracker.util :refer [statement-success?] :as util]
            [clojure.algo.generic.functor :refer [fmap]]
            [yesql.core :refer [defqueries]]
            ;; For protocol extensions
            [clj-time.jdbc]))

(defqueries "time_tracker/timers/sql/db.sql")

(def timer-keys [:id :task_id :started_time :duration :time_created :notes])

(defn- hyphenize
  [thing]
  (if (keyword? thing)
    (util/hyphenize thing)
    thing))

(defn epochize
  [thing]
  (if (instance? org.joda.time.DateTime thing)
    (util/to-epoch-seconds thing)
    thing))

(defn transform-timer-map
  [timer-map]
  (-> timer-map
      (select-keys timer-keys)
      (util/transform-map hyphenize epochize)))

(defn has-timing-access?
  [connection google-id task-id]
  (let [authorized-query-result (first (has-timing-access-query {:google_id  google-id
                                                                 :permission "admin"
                                                                 :task_id task-id}
                                                                {:connection connection}))]
    (statement-success? (:count authorized-query-result))))

(defn owns?
  "True if a user owns a timer."
  [connection google-id timer-id]
  (let [owns-query-result (first (owns-query {:google_id google-id
                                              :timer_id  timer-id}
                                             {:connection connection}))]
    (statement-success? (:count owns-query-result))))

(defn create!
  "Creates and returns a timer."
  ([connection task-id google-id created-time notes]
   (create! connection task-id google-id created-time notes 0))
  ([connection task-id google-id created-time notes duration]
   (-> (create-timer-query<! {:google_id    google-id
                              :task_id   task-id
                              :created_time created-time
                              :notes        notes
                              :duration     duration}
                             {:connection connection})
       (transform-timer-map))))

(defn update!
  "Set the elapsed duration of the timer."
  [connection timer-id duration current-time notes]
  (when (statement-success? (update-timer-query! {:duration     duration
                                                  :timer_id     timer-id
                                                  :current_time current-time
                                                  :notes        notes}
                                                 {:connection connection}))
    (-> (retrieve-timer-query {:timer_id timer-id}
                              {:connection connection})
        (first)
        (transform-timer-map))))

(defn delete!
  "Deletes a timer. Returns false if the timer doesn't exist."
  [connection timer-id]
  (statement-success?
   (delete-timer-query! {:timer_id  timer-id}
                        {:connection connection})))

(defn retrieve-all
  "Retrieves all timers."
  [connection]
  (retrieve-all-query {} {:connection  connection
                          :identifiers util/hyphenize
                          :row-fn      #(fmap epochize %)}))

(defn retrieve-between
  "Retrieves all timers created between `start-epoch` and `end-epoch`.
  `start-epoch` is inclusive and `end-epoch` is exclusive."
  [connection start-epoch end-epoch]
  (retrieve-between-query {:start_epoch start-epoch
                           :end_epoch   end-epoch}
                          {:connection  connection
                           :identifiers util/hyphenize
                           :row-fn      #(fmap epochize %)}))

(defn retrieve-between-authorized
  "Retrieves all timers created between `start-epoch` and `end-epoch`
  and owned by `google-id`.
  `start-epoch` is inclusive and `end-epoch` is exclusive."
  [connection google-id start-epoch end-epoch]
  (retrieve-between-authorized-query {:start_epoch start-epoch
                                      :end_epoch   end-epoch
                                      :google_id   google-id}
                                     {:connection  connection
                                      :identifiers util/hyphenize
                                      :row-fn      #(fmap epochize %)}))

(defn retrieve-authorized-timers
  "Retrieves all timers the user is authorized to modify."
  [connection google-id]
  (->> (retrieve-authorized-timers-query {:google_id google-id}
                                         {:connection connection})
       (map transform-timer-map)))

(defn retrieve-started-timers
  "Retrieves all timers which the user is authorized to modify
  and which are started."
  [connection google-id]
  (->> (retrieve-started-timers-query {:google_id google-id}
                                      {:connection connection})
       (map transform-timer-map)))

(defn start!
  "Starts a timer if the timer is not already started.
  Returns the started timer or nil."
  [connection timer-id current-time]
  (when (statement-success? (start-timer-query! {:timer_id     timer-id
                                                 :current_time current-time}
                                                {:connection connection}))
    (-> (retrieve-timer-query {:timer_id  timer-id}
                              {:connection connection})
        (first)
        (transform-timer-map))))

(defn stop!
  "Stops a timer if the timer is not already stopped.
  Returns the stopped timer or nil."
  [connection timer-id current-time]
  (let [{:keys [duration] :as timer} (first (retrieve-timer-query {:timer_id timer-id}
                                                                  {:connection connection}))]
    ;; When the timer is started
    (when (:started_time timer)
      (let [started-time (util/to-epoch-seconds (:started_time timer))
            new-duration (+ duration (- current-time started-time))]
        (when (and (>= new-duration duration)
                   (statement-success? (stop-timer-query! {:timer_id     timer-id
                                                           :current_time current-time
                                                           :duration     new-duration}
                                                          {:connection connection})))
          (-> (retrieve-timer-query {:timer_id  timer-id}
                                    {:connection connection})
              (first)
              (transform-timer-map)))))))

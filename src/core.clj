(ns core
  (:require [clojure.string :as str]
            [cheshire.core :as json]))

(defn ->duration
  "Take events and calculate the durations `:d` of each individual event based on the
  `:t` values."
  [evts]
  (map (fn [[evt1 evt2]]
         (-> evt2
             (assoc :d (- (:t evt2) (:t evt1)))))
       (partition 2 1 (cons {:t 0M} evts))))

(defn ->time
  "Take events and calculate the cumulative time `:t` values based on the
  durations `:d`. Old `:t` values are overriden."
  [evts]
  (second
   (reduce (fn [[t evts] evt]
             (let [new-t (+ t (:d evt))]
               [new-t (conj evts (-> evt
                                     (assoc :t new-t)))]))
           [0M []] evts)))

(defn read-cast
  "Read a cast file to an intermidiate format."
  [filename]
  ;; https://github.com/asciinema/asciinema/blob/develop/doc/asciicast-v2.md
  (let [[header & events] (->> (slurp filename)
                               (str/split-lines)
                               (map #(json/parse-string % true)))]

    {:header header
     :events (->duration
              (map-indexed (fn [i [time _event-type event-data]]
                             {:id [i]
                              :t (bigdec time)
                              ;; discard type. It's always "o"
                              :data event-data})
                           events))}))

(defn write-cast
  "Write intermidiate format `data` to a given `filename`. "
  [data filename]
  (let [header (:header data)
        type "o" ;; type is always "o"
        events (map (fn [{:keys [t data]}]
                      [t type data]) (->time (:events data)))]

    (->> (cons header events)
         (map #(json/generate-string %))
         (str/join "\n")
         (spit filename))))

(defn pp
  "'Pretty print' data.

  Well, this doesn't really print anything but returns the data in a format that
  looks nice in REPL output when evaluated."
  [data]
  (map (juxt :id :t :d :data) (->time (:events data))))

(defn- reduce-part [f evts start end]
  (reduce
   (fn [memo e]
     (if (:reducing memo)
       ;; doing it
       (if (= (:id e) end)
         ;; found end
         (assoc memo
                :reducing false
                :evts (vec (f (:evts memo) e)))

         (assoc memo
                :reducing true
                :evts (vec (f (:evts memo) e))))

       ;; not doing it
       (if (= (:id e) start)
         ;; found start
         (assoc memo
                :reducing true
                :evts (vec (f (:evts memo) e)))

         ;; not yet started / or ended
         (assoc memo
                :reducing false
                :evts (conj (:evts memo) e)))))
   {:reducing false :evts []}
   evts))

(defmulti apply-op
  "Apply operation"
  (fn [_ [op-name]] op-name))

(defmethod apply-op :cut-start [data [_ {:keys [end]}]]
  ;; :cut-start cuts from the beginnign to the given end.
  (update data :events #(:evts
                         (reduce
                          (fn [memo e]
                            (if (:cut memo)
                              ;; cutting
                              (if (= (:id e) end)
                                ;; found end
                                (assoc memo :cut false)

                                memo)

                              (update memo :evts conj e)))
                          {:cut true :evts []}
                          %))))

(defmethod apply-op :quantize [data [_ opts]]
  (update data :events
          #(map (fn [{:keys [d] :as evt}]
                  (assoc evt :d (min (max d (:min opts)) (:max opts))))
                %)))

(defmethod apply-op :pause [data [_ {:keys [id d]}]]
  ;; Add pause
  (update data :events
          (fn [evts]
            (map (fn [e] (if (= (:id e) id)
                           (update e :d #(+ % d))
                           e))
                 evts))))

(defmethod apply-op :split [data [_ {:keys [start end d]}]]
  (let [r 10M] ;; Adds some randomness to the splitted value, looks more realistic typing.
    (update data :events
            #(:evts
              (reduce-part
               (fn [evts e]
                 (let [{:keys [data id]} e]
                   (if (or (str/blank? data)
                           (= (count data) 1)
                           (re-find #"\p{C}" data))
                     (conj evts e)

                     ;; split only if a) not blank b) count more than 1 c) does not contain non-printable characters
                     (concat evts (map-indexed (fn [i chr]
                                                 {:id (conj id i)
                                                  :d (+ d (* d (- (rand r) (/ r 2M)))) ;; lol a bit confusing logic here.
                                                  :data chr}) (str/split (:data e) #""))))))
               %
               start
               end)))))

(defn apply-ops [data ops]
  (reduce apply-op data ops))

(comment
  ;; Example.
  ;; This is the script that I used to produce the gif for quering
  ;; listings by multiple IDs

  (-> "../work2.cast"
      read-cast
      (apply-ops [[:cut-start {:end [44]}]
                  [:split {:start [45] :end [100] :d 0.035M}]
                  [:split {:start [260] :end [286] :d 0.025M}]
                  [:split {:start [299] :end [373] :d 0.025M}]
                  [:split {:start [382] :end [484] :d 0.025M}]
                  [:quantize {:min 0.01M :max 0.1M}]
                  [:pause {:id [55], :d 0.5M}]
                  [:pause {:id [79], :d 0.5M}]
                  [:pause {:id [119], :d 0.5M}]

                  [:pause {:id [260], :d 0.8M}]
                  [:pause {:id [286], :d 0.8M}]
                  [:pause {:id [299], :d 0.8M}]
                  [:pause {:id [373], :d 0.8M}]
                  [:pause {:id [382], :d 0.8M}]
                  [:pause {:id [484], :d 0.8M}]

                  [:pause {:id [493], :d 2M}]

                  ])
      #_(pp) ;; debugging
      (write-cast "../out2.cast")
      )
  )

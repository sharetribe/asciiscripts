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

(defmethod apply-op :cut-end [data [_ {:keys [start]}]]
  ;; :cut-end cuts from the given start to the end.
  (update data :events #(:evts
                         (reduce
                          (fn [memo e]
                            (if (:cut memo)
                              ;; cutting
                              memo

                              (if (= (:id e) start)
                                (assoc memo :cut true)
                                (update memo :evts conj e))))
                          {:cut false :evts []}
                          %))))

(defmethod apply-op :cut [data [_ {:keys [start end]}]]
  ;; :cut-end cuts from the given start to the given end.
  (update data :events #(:evts
                         (reduce
                          (fn [memo e]
                            (if (:cut memo)
                              ;; cutting
                              (if (= (:id e) end)
                                (-> memo
                                    (assoc :cut false)
                                    (update :evts conj e))
                                memo)

                              (if (= (:id e) start)
                                (assoc memo :cut true)
                                (update memo :evts conj e))))
                          {:cut false :evts []}
                          %))))

(defn- merge-events
  [a b]
  (update a :data str (:data b)))

(defmethod apply-op :merge [data [_ {:keys [start end]}]]
  ;; :combines output from start to end into single event
  (update data :events #(:evts
                         (reduce
                          (fn [memo e]
                            (if (:merge memo)
                              ;; combining
                              (let [merged (merge-events (:merged memo) e)]
                                (if (= (:id e) end)
                                  (-> memo
                                      (assoc :merge false)
                                      (update :evts conj merged))
                                  (assoc memo :merged merged)))

                              (if (= (:id e) start)
                                (-> memo
                                    (assoc :merge true)
                                    (assoc :merged e))
                                (update memo :evts conj e))))
                          {:merge false :evts []}
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

(defmethod apply-op :pause-matching [data [_ {:keys [d match]}]]
  ;; Add pause
  (update data :events
          (fn [evts]
            (map (fn [e] (if (re-find match (:data e))
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
                           ;; control character
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

(defmethod apply-op :resize [data [_ {:keys [width height]}]]
  (update data :header (fn [header]
                         (cond-> header
                           width (assoc :width width)
                           height (assoc :height height)))))

(defmethod apply-op :str-replace [data [_ {:keys [match replacement]}]]
  (update data
          :events
          (fn [evts]
            (map (fn [e]
                   (update e :data str/replace match replacement))
             evts))))

(defn apply-ops [data ops]
  (reduce apply-op data ops))

(defn return-ids-of-matching [{:keys [events]} match]
  (->> events
       (filter #(re-find match (:data %)))
       (map #(first (:id %)))))

(comment
  ;; Example.

  ;; This is the script I used to produce git for querying users

  (-> "../flex-integration-sdk-js/user-search-demo2.cast"
      read-cast
      (apply-ops [[:cut-start {:end [1477]}]
                  ;; Strip undefined REPL output for comments and other expressions that return undefined
                  [:str-replace {:match #"^\[90mundefined\[39m\r\n" :replacement ""}]
                  [:str-replace {:match #"harry\.hill" :replacement "harry.smith"}]
                  [:str-replace {:match #"(.*true.*)rating:" :replacement "$1 rating:"}]
                  [:str-replace {:match #"via:" :replacement "\tvia:"}]
                  [:split {:start [1487] :end [2062] :d 0.035M}]
                  [:split {:start [2072] :end [2770] :d 0.035M}]
                  [:split {:start [2778] :end [2882] :d 0.035M}]
                  [:split {:start [2891] :end [2950] :d 0.035M}]
                  [:quantize {:min 0.01M :max 0.1M}]
                  [:cut {:start [2717] :end [2719]}]
                  [:merge {:start [2148] :end [2566]}]

                  [:pause {:id [2072] :d 0.8M}]
                  [:pause {:id [2567 0] :d 2M}]
                  [:pause {:id [2778] :d 0.8M}]
                  [:pause {:id [2891] :d 0.8M}]
                  [:pause {:id [2959] :d 2M}]

                  [:cut-end {:start [2960]}]])

      (write-cast "../flex-integration-sdk-js/user-search-demo-edited.cast"))

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
      (pp) ;; debugging
      #_(write-cast "../out2.cast")
      )

  (-> "../array-params-serialize.cast"
      read-cast
      (apply-ops [[:resize {:width 86 :height 25}]
                  [:cut-start {:end [31]}]
                  [:split {:start [31] :end [210]}]
                  [:quantize {:min 0.1M :max 0.1M}]
                  [:pause {:id [216], :d 2M}]])
      #_(pp) ;; debugging
      (write-cast "../array-params-serialize-edited.cast")
      )

  (-> (read-cast "./gifs/example/original.cast")
      (apply-ops [[:cut-start {:end [21]}]
                  [:quantize {:min 0.01M :max 0.1M}]
                  [:pause {:id [23] :d 1M}]
                  [:pause {:id [42] :d 2M}]
                  [:cut-end {:start [85]}]
                  [:pause {:id [84] :d 2M}]
                  ])
      (write-cast "./gifs/example/improved.cast")
      #_(pp)
      )

  ; Find IDs of where line breaks exist
  (-> (read-cast "./gifs/user-deletion/pre.cast")
      (return-ids-of-matching #"\r\r\n")
      )
  ; => (4 70 118 171 216 261 310 323 328 389 415 465 469 509 564 584 624 656 695 711 729 734 769 816 820 832 837)

  ; Delete user gif
  (-> (read-cast "./gifs/user-deletion/pre.cast")
      (apply-ops [
                  [:resize {:width 80 :height 35}]
                  [:cut-start {:end [329]}]
                  [:str-replace {:match #"undefined" :replacement ""}]
                  [:quantize {:min 0.04M :max 0.5M}]
                  [:pause {:id [469] :d 1.5M}]
                  [:pause {:id [734] :d 1.5M}]
                  [:pause {:id [820] :d 1.5M}]
                  [:cut-end {:start [821]}]
                  ])
      (write-cast "./gifs/user-deletion/post.cast")
      (pp)
      )

  )

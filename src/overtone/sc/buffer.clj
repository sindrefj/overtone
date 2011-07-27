(ns overtone.sc.buffer
  (:use
    [overtone util]
    [overtone.sc core allocator]))

(declare buffer-info)

;; ## Buffer functions
;;
; TODO: Look into multi-channel buffers.  Probably requires adding multi-id allocation
; support to the bit allocator too...
; size is in samples
(defn buffer
  "Allocate a new zero filled buffer for storing audio data with the specified size and num-channels."
  ([size] (buffer size 1))
  ([size num-channels]
     (let [id     (alloc-id :audio-buffer)
           ready? (atom false)
           info   (atom {})]
       (on-server-sync
        #(snd "/b_alloc" id size num-channels)
        #(do
           (reset! ready? true)
           (reset! info (buffer-info id))))

       (with-meta {:id id
                   :size size
                   :ready? ready?}
         {:type ::buffer}))))

(defn buffer-ready?
  "Check whether a sample or a buffer has completed allocating and/or loading data."
  [buf]
  @(:ready? buf))

(defn sbuffer
  "Allocate a new buffer synchronously. Halts the current thread until the buffer has been succesfully allocated"
  ([size] (sbuffer size 1))
  ([size num-channels]
     (wait-until-connected)
     (let [buf (buffer size num-channels)]
       (while (not (buffer-ready? buf))
         (Thread/sleep 50))
       buf)))

(defn buffer? [buf]
  (isa? (type buf) ::buffer))

(defn- buf-or-id [b]
  (cond
    (buffer? b) (:id b)
    (number? b) b
    :default (throw (IllegalArgumentException. "Not a valid buffer: " b))))

(defn buffer-free
  "Free an audio buffer and the memory it was consuming."
  [buf]
  (let [id (cond
             (buffer? buf) (:id buf)
             (number? buf) buf
             :default (throw (IllegalArgumentException. "Not a valid buffer or buffer id.")))]
    (snd "/b_free" id)
    (free-id :audio-buffer id)
    :done))

(defn buffer-read
  "Read a section of an audio buffer."
  [buf start len]
  (assert (buffer? buf))
  (loop [reqd 0]
    (when (< reqd len)
      (let [to-req (min MAX-OSC-SAMPLES (- len reqd))]
        (snd "/b_getn" (:id buf) (+ start reqd) to-req)
        (recur (+ reqd to-req)))))
  (let [samples (float-array len)]
    (loop [recvd 0]
      (if (= recvd len)
        samples
        (let [msg-p (recv "/b_setn")
              msg (await-promise! msg-p)
              [buf-id bstart blen & samps] (:args msg)]
          (loop [idx bstart
                 samps samps]
            (when samps
              (aset-float samples idx (first samps))
              (recur (inc idx) (next samps))))
          (recur (+ recvd blen)))))))

(defn buffer-write!
  "Write into a section of an audio buffer.
   Modifies the buffer in place on the server."
  [buf start len data]
  (assert (buffer? buf))

  (apply snd "/b_setn" (:id buf) start len (map double data)))

(defn buffer-fill!
  "Fill a buffer range with a single value.
   Modifies the buffer in place on the server."
  [buf start len val]
  (assert (buffer? buf))

  (snd "/b_fill" (:id buf) start len (double val)))

(defn buffer-set!
  "Write a single value into a buffer.
  Modifies the buffer in place on the server."
  [buf index val]
  (assert (buffer? buf))

  (snd "/b_set" (:id buf) index (double val)))

(defn buffer-get
  "Read a single value from a buffer."
  [buf index]
  (assert (buffer? buf))

  (let [res (recv "/b_set")]
    (snd "/b_get" (:id buf) index)
    (last (:args (await-promise! res)))))

(defn buffer-save
  "Save the float audio data in an audio buffer to a wav file."
  [buf path & args]
  (assert (buffer? buf))

  (let [arg-map (merge (apply hash-map args)
                       {:header "wav"
                        :samples "float"
                        :n-frames -1
                        :start-frame 0
                        :leave-open 0})
        {:keys [header samples n-frames start-frame leave-open]} arg-map]

    (snd "/b_write" (:id buf) path header samples
         n-frames start-frame
         (if leave-open 1 0))
    :done))

(defmulti buffer-id type)
(defmethod buffer-id java.lang.Integer [id] id)
(defmethod buffer-id ::buffer [buf] (:id buf))
(defmethod buffer-id ::buffer-info [buf-info] (:id buf-info))

(defmulti buffer-size type)
(defmethod buffer-size ::buffer [buf] (:size buf))
(defmethod buffer-size ::buffer-info [buf-info] (:n-frames buf-info))

(defn buffer-data
  "Get the floating point data for a buffer on the internal server."
  [buf]
  (let [buf-id (buffer-id buf)
        snd-buf (.getSndBufAsFloatArray @sc-world* buf-id)]
    snd-buf))

(defn buffer-info
  [buf]
  (let [mesg-p (recv "/b_info")
        buf-id (buffer-id buf)
        _   (snd "/b_query" buf-id)
        msg (await-promise! mesg-p)
        [buf-id n-frames n-channels rate] (:args msg)]
    (with-meta     {:n-frames n-frames
                    :n-channels n-channels
                    :rate rate
                    :id buf-id}
      {:type ::buffer-info})))

;;TODO Check to see if this can be removed
(defn sample-info [s]
  (buffer-info (:buf s)))

(defn num-frames
  [buf]
  (:n-frames  @(:info buf)))

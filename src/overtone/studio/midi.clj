(ns overtone.studio.midi
  #^{:author "Sam Aaron and Jeff Rose"
     :doc "A high level MIDI API for sending and receiving messages with
           external MIDI devices and automatically hooking into
           Overtone's event system." }
  (:use [overtone.sc.dyn-vars]
        [overtone.at-at :only [mk-pool every]]
        [overtone.libs event counters]
        [overtone.sc.defaults :only [INTERNAL-POOL]]
        [overtone.helpers.system :only [mac-os?]]
        [overtone.config.store :only [config-get]]
        )
  (:require [overtone.config.log :as log]
            [overtone.midi :as midi]))

(defonce midi-control-agents* (atom {}))
(defonce poly-players* (atom {}))

(declare connected-midi-devices)
(declare connected-midi-receivers)

(defn midi-mk-full-device-key
  "Returns a unique key for the specific device. In the case of multiple
   identical devices, the final integer of the key, dev-num, will be
   different to ensure key uniqueness.

   Is able to handle either a connected MIDI device stored in this
   namespace via or a raw MIDI device map from overtone.midi. "
  [dev]
  (or (::full-device-key dev)
      (let [dev-num (or (::dev-num dev)
                        (::dev-num (get (connected-midi-devices) (:device dev)))
                        -1)]
        [:midi-device (dev :vendor) (dev :name) (dev :description) dev-num])))

(defn- midi-find-connected
  [search devs-or-recvrs]
  (let [filter-pred (fn [dev]
                      (let [key-as-str (str (midi-mk-full-device-key dev))]
                        (if (= java.util.regex.Pattern (type search))
                          (re-find search key-as-str)
                          (.contains key-as-str search))))]
    (filter filter-pred devs-or-recvrs)))

(defn midi-find-connected-devices
  "Returns a list of connected MIDI devices where the full device key
   either contains the search string or matches the search regexp
   depending on the type of parameter supplied"
  [search]
  (midi-find-connected search (connected-midi-devices)))

(defn midi-find-connected-receivers
  "Returns a list of connected MIDI receivers where the full device key
   either contains the search string or matches the search regexp
   depending on the type of parameter supplied"
  [search]
  (midi-find-connected search (connected-midi-receivers)))

(defn midi-find-connected-device
  "Returns the first connected MIDI device found where the full device
   key either contains the search string or matches the search regexp
   depending on the type of parameter supplied"
  [search]
  (first (midi-find-connected-devices search)))

(defn midi-find-connected-receiver
  "Returns the first connected MIDI receiver found where the full device
   key either contains the search string or matches the search regexp
   depending on the type of parameter supplied"
  [search]
  (first (midi-find-connected-receivers search)))

(defn midi-mk-full-device-event-key
  "Creates the device-specific part of the key used for events generated
  by incoming MIDI messages."
  [dev command]
  (concat (midi-mk-full-device-key dev) [command]))

(defn midi-mk-full-control-event-key
  "Creates the full key used for events generated by incoming MIDI
  messages. Using this key can allow you to detect events from specific
  devices rather than just general control events."
  [dev command control-id]
  (concat (midi-mk-full-device-event-key dev command) [control-id]))


(defn midi-device-keys
  "Return a list of device event keys for the available MIDI devices"
  []
  (map midi-mk-full-device-key (vals (connected-midi-devices))))

(defn- midi-control-handler
  [state-atom handler mapping msg]
  (let [[ctl-name scale-fn] (get mapping (:note msg))
        ctl-val  (scale-fn (:velocity msg))]
    (swap! state-atom assoc ctl-name ctl-val)
    (handler ctl-name ctl-val)))

(defn midi-inst-controller
  "Create a midi instrument controller for manipulating the parameters of an instrument
  using an external device.  Requires an atom to store the state of the parameters, a
  handler that will be called each time a parameter is modified, and a mapping table to
  specify how midi control messages should manipulate the parameters.

  (def ding-mapping
    {22 [:attack     #(* 0.3 (/ % 127.0))]
     23 [:decay      #(* 0.6 (/ % 127.0))]
     24 [:sustain    #(/ % 127.0)]
     25 [:release    #(/ % 127.0)]})

  (def ding-state (atom {}))

  (midi-inst-controller ding-state (partial ctl ding) ding-mapping)
  "
  [state-atom handler mapping]
  (let [ctl-key (keyword (gensym 'control-change))]
    (on-event [:midi :control-change]
              #(midi-control-handler state-atom handler mapping %)
              ctl-key)))

(defn midi-player-stop
  ([]
     (remove-event-handler [::midi-poly-player :midi :note-on])
     (remove-event-handler [::midi-poly-player :midi :note-off]))
  ([player-or-key]
     (if (keyword? player-or-key)
       (midi-player-stop (get @poly-players* player-or-key))
       (let [player player-or-key]
         (when-not (= :overtone.studio.midi-player/midi-poly-player (type player))
           (throw (IllegalArgumentException. (str "Expected a midi-poly-player. Got: " (prn-str (type player))))))
         (remove-event-handler (:on-key player))
         (remove-event-handler (:off-key player))
         (reset! (:playing? player) false)
         (swap! poly-players* dissoc (:player-key player))
         player))))


(defn midi-capture-next-control-input
  "Returns a simple map representing next modified controller. Will
  block the current thread until a new MIDI control-change event is
  received.

  Useful for detecting controller information. If the argument with-key?
  is set to true, the full event key will be associated with the
  resulting map."
  ([] (midi-capture-next-control-input false))
  ([with-key?]
     (let [p (promise)]
       (oneshot-event [:midi :control-change]
                      (fn [msg]
                        (let [{controller :data1 val :data2} msg
                              device-name                    (get-in msg [:device :name])
                              res {:controller controller :value val}
                              res (if with-key?
                                    (assoc res :key (midi-mk-full-device-event-key (:device msg) :control-change))
                                    res)]

                          (deliver p res)))
                      ::print-next-control-input)
       @p)))

(defn midi-capture-next-controller-key
  "Returns a vector representing the unique key for the next modified
  controller."
  []
  (:key (midi-capture-next-control-input true)))

(defn midi-capture-next-controller-control-key
  "Returns a vector representing the unique key for the specific control
  of the next modified controller. This is the key used for controller
  specific MIDI events."
  []
  (let [next-input (midi-capture-next-control-input true)]
    (vec (concat (:key next-input) [(:controller next-input)]))))

(defn midi-mk-control-key-keyword
  [prefix control-key]
  (keyword (str prefix control-key)))

(defn- mk-control-key-keyword-for-agent
  [control-key]
  (midi-mk-control-key-keyword "overtone.studio.midi/update-agent-for-control-" control-key))

(defn midi-agent-for-control
  "Returns an agent representing the current value of a
  controller-specific control identified by a fully-qualified MIDI event
  key such as that generated by midi-mk-full-control-event-key. If the
  agent doesn't exist, it is created and cached. Subsequent calls with
  the same control-key will return the same agent.

  Agents are used because the event to update them can be safely handled
  synchronously (with on-sync-event) without watchers being able to
  block the thread generating the MIDI events. This also means that
  incoming events are sent to the agent in the correct order whereas if
  the thread pool were used (via on-event), the incoming events may be
  arbitrarily ordered."
  [control-key]
  (let [control-agents (swap! midi-control-agents*
                              (fn [prev]
                                (if (get prev control-key)
                                  prev
                                  (let [new-control-agent (agent 0)]
                                    (on-sync-event control-key
                                                   (fn [msg]
                                                     (send new-control-agent
                                                           (fn [old-val]
                                                             (:data2 msg))))
                                                   (mk-control-key-keyword-for-agent control-key))
                                    (assoc prev control-key new-control-agent)))))]
    (get control-agents control-key)))

(defn- handle-incoming-midi-event
  "Place incoming midi-event onto the global event stream."
  [dev msg & [ts]]
  (let [command       (:command msg)
        data2-f       (float (/ (:data2 msg) 127))
        msg           (assoc msg :data2-f data2-f :velocity-f data2-f)
        dev-key       (midi-mk-full-device-key dev)
        dev-event-key (midi-mk-full-device-event-key dev command)
        msg           (assoc msg :dev-key dev-key)]
    (event [:midi command] msg)
    (event (midi-mk-full-device-key dev) msg)
    (event (midi-mk-full-control-event-key dev command (:data1 msg)) msg)
    (event dev-event-key msg)))

(defn- handle-incoming-midi-sysex
  "Place incoming midi sysex message onto the global event stream."
  [dev msg & [ts]]
  (let [dev-key (midi-mk-full-device-key dev)
        msg     (assoc msg :dev-key dev-key)]
    (event (midi-mk-full-device-key dev) :sysex msg)))

(defn- remove-duplicate-devices
  "Removes all duplicate devices, where a duplicate is defined as a
   device map with the same :device value."
  [devs]
  (vals (into {} (map (fn [dev] [(:device dev) dev]) devs))))

(defn- detect-midi-devices
  "Returns a set of MIDI device maps filtered to remove unwanted devices
   such as the Java Real Time Sequencer and duplicates"
  []
  (let [devs   (midi/midi-sources)
        devs   (remove-duplicate-devices devs)
        devs   (map #(assoc % ::dev-num (next-id
                                         (str "overtone.studio.midi - device - "
                                              (:vendor %)
                                              (:name %)
                                              (:description %))))
                    devs)
        devs   (map #(assoc % ::full-device-key (midi-mk-full-device-key %)) devs)]
    devs))

(defn- detect-midi-receivers
  []
  (let [rcvs   (midi/midi-sinks)
        rcvs   (remove-duplicate-devices rcvs)
        rcvs   (map #(assoc % ::dev-num (next-id
                                         (str "overtone.studio.midi - receiver - "
                                              (:vendor %)
                                              (:name %)
                                              (:description %))))
                    rcvs)

        rcvs   (map #(assoc % ::full-device-key (midi-mk-full-device-key %)) rcvs)]
    rcvs))

(defn- add-listener-handles!
  "Adds listener handles to send incoming messages to Overtone's event
   stream. Devices that a handler can't be added to are dropped. Returns
   a filtered and modified sequence of device maps"
  [devs]
  (doall (filter
          (fn [dev]
            (try
              (midi/midi-handle-events (midi/midi-in dev)
                                  #(handle-incoming-midi-event dev %1)
                                  #(handle-incoming-midi-sysex dev %1))
              true
              (catch Exception e
                (log/warn "Can't listen to midi device: " dev "\n" e)
                false)))
          devs)))

(defonce ^:private connected-midi-devices*
  (-> (detect-midi-devices) add-listener-handles!))

(defonce ^:private connected-midi-receivers*
  (map midi/midi-out (detect-midi-receivers)))

(defn connected-midi-devices
  "Returns a sequence of device maps for all 'connected' MIDI
   devices. By device, we mean a MIDI unit that is capable of sending
   messages (such as a MIDI piano). By connected, we mean that Overtone
   is aware of the device and has added event handlers to emit incoming
   messages from the device as unique events.

   This currently returns a list which was created and cached at boot
   time. Therefore, devices connected after boot will not be
   available. We are considering work-arounds to this issue for a future
   release."
  []
  connected-midi-devices*)

(defn connected-midi-receivers
  "Returns a sequence of device maps for all 'connected' MIDI
   receivers. By receiver, we mean a MIDI unit that is capable of
   receiving messages. By connected, we mean that Overtone is aware of
   the device.

   This currently returns a list which was created and cached at boot
   time. Therefore, devices connected after boot will not be
   available. We are considering work-arounds to this issue for a future
   release."
  []
  connected-midi-receivers*)

(defn midi-device-num
  "Returns the device number for the specified MIDI device"
  [dev]
  (::dev-num dev))

(defn midi-full-device-key
  "Returns the full device key for the specified MIDI device"
  [dev]
  (::full-device-key dev))

(defn midi-sysex
  "Send a midi System Exclusive msg made up of the bytes in byte-seq
   byte-array, sequence of integers, longs or a byte-string to the
   receiver.  If a byte string is specified, must only contain bytes
   encoded as hex values.  Commas, spaces, and other whitespace is
   ignored.

   See connected-midi-receivers for a full list of available receivers."
  [rcv byte-seq]
  (midi/midi-sysex rcv byte-seq))

(defn midi-control
  "Send a MIDI control msg to the receiver. See connected-midi-receivers
   for a full list of available receivers."
  ([rcv ctl-num val]
     (midi/midi-control rcv ctl-num val))
  ([rcv ctl-num val channel]
     (midi/midi-control rcv ctl-num val channel)))

(defn midi-note-on
  "Send a MIDI note on msg to the receiver. See connected-midi-receivers
   for a full listof available receivers."
  ([rcv note-num vel]
     (midi/midi-note-on rcv note-num vel))
  ([rcv note-num vel channel]
     (midi/midi-note-on rcv note-num vel channel)))

(defn midi-note-off
  "Send a MIDI note off msg to the receiver. See connected-midi-receivers
   for a full list of available receivers."
  ([rcv note-num]
     (midi/midi-note-off rcv note-num))
  ([rcv note-num channel]
     (midi/midi-note-off rcv note-num channel)))

(defn midi-note
  "Send a midi on/off msg pair to the receiver. The off message will be
   sent dur ms after the on message resulting in the note being 'played'
   for dur ms.

   See connected-midi-receivers for a full list of available receivers."
  ([rcv note-num vel dur]
     (midi/midi-note rcv note-num vel dur))
  ([rcv note-num vel dur channel]
     (midi/midi-note rcv note-num vel dur channel)))

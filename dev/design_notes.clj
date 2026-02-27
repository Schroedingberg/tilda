(ns dev.design-notes
  "HISTORICAL DESIGN NOTES (Feb 2026)
   
   This file captures original design thinking during initial development.
   Some ideas were implemented, others evolved differently. Read as context,
   not as current source of truth.
   
   For current state: see README.md and .copilot-instructions.md")

;; -----------------------------------------------------------------------------
;; ORIGINAL NOTES BELOW - may reflect outdated ideas
;; -----------------------------------------------------------------------------

;; I guess if i want to book the car for a certain time, the system needs to know:
;; - Who is booking (tenant)
;; - When do they want to book (start and end time)
;; So let's just start with that and see where it takes us.
{:tenant-name "Val"
 :start-date (java.time.Instant/parse "2026-02-28T00:00:00Z")
 :end-date (java.time.Instant/parse "2026-03-01T00:00:00Z")}

;; Now we could just take this as is and be done with it.
;; That would mean:
;; 1. Everyone can just enter their desired period
;; 2. Periods can overlap and contradict
;; So that gives us more detail on the domain:
;; - We need to handle contradicting bookings
;; I see two ways:
;; 1. Classic CRUD: First come first serve, if you're booked, you're booked. That's your slot, and no one else can book.
;; 2. Transaction based: We track the fact that someone booked a period. We show that in a booking calendar, just as with 1.
;;    But this allows us to:
;;     - Use different conflict resolution strategies, e.g. first come first serve, but also negotiation (e.g. you could get a notification if someone wants to book your slot)
;;   As long as we have the booking transaction, we can also easily track changes to it, e.g. if someone cancels or changes their booking.
;;   This also allows us to easily implement a waiting list, e.g. if someone cancels we can easily notify the next person in line.
;; So let's go with 2, and see how that works out.
;; We basically just use the same data as above, but frame it as an event/transaction by adding a :type.
{:type :slot-reserved
 :tenant-name "Val"
 :start-date (java.time.Instant/parse "2026-02-28T00:00:00Z")
 :end-date (java.time.Instant/parse "2026-03-01T00:00:00Z")}

;; Pretty good. I am wondering whether I should add metadata like a tx-id, a timestamp, etc. myself or if i should let xtdb handle that.
;; I guess for testing it would be good to have it in code, but in production I would probably want to let xtdb handle it, as it can guarantee uniqueness and consistency.
;; So let's try what xtdb gives us:

(let [tx {:type :slot-reserved
          :tenant-name "Val"
          :start-date (java.time.Instant/parse "2026-02-28T00:00:00Z")
          :end-date (java.time.Instant/parse "2026-03-01T00:00:00Z")}]
  (xt/submit-tx node [[:put-docs :bookings tx]]))
;; Ah it does not accept this!
;; That means xtdb probably wants an xt/id. I guess I'll give it a uuid then.
(let [tx {:type :slot-reserved
          :tenant-name "Val"
          :start-date (java.time.Instant/parse "2026-02-28T00:00:00Z")
          :end-date (java.time.Instant/parse "2026-03-01T00:00:00Z")}]
  (xt/submit-tx node [[:put-docs :bookings (assoc tx :xt/id (java.util.UUID/randomUUID))]]))

;; Here we go.
;; Let's see if we can query it back out.
(xt/q node '(from :bookings [*]))
;; That didn't work. It only has the ones from the comment section.
(xt/q node "SELECT * FROM bookings")
;; Oh, nevermind, it was just too many to display in overlay. Repl shows them.

;; Ok, so we can easily capture what our tenants want to book, or rather when.
;; Lets see if we can tell different tenants apart!

(let [tx {:type :slot-reserved
          :tenant-name "Flo"
          :start-date (java.time.Instant/parse "2026-02-28T00:00:00Z")
          :end-date (java.time.Instant/parse "2026-03-01T00:00:00Z")}]
  (xt/submit-tx node [[:put-docs :bookings (assoc tx :xt/id (java.util.UUID/randomUUID))]]))


(xt/q node "SELECT * FROM bookings WHERE tenant-name = 'Val'")
(xt/q node '(from :bookings [*] :where [:= tenant-name "Val"]))
;; Hm, that doesn't work yet how I would expect
(xt/q node '(from :bookings [*] :where [:= tenant-name "Val"]))


;; Well let's leave that for now and do more modelling.
;; So since this is only for a small group, we'll probably rely a lot on social interaction.
;; That being said, it would be good if a booking is decided at some point, to reward early planning.
;; So how can one do that?
;; We could say that a booking is confirmed after a certain period of days.
;; Or we could collect approval.
;; My preference is that everyone is asked to confirm booking. With around 10 users that's reasonable.
;; We can grant users like a week of time to object, and if they dont, thats considered approval.
;; This is actually more elegant than a fixed pre-planning period (have to book 30 days in advance), because it
;; implicitly adapts to demand.

;; So, say we know that the car is reserved by Flo the last weekend in february. Let's say she did that a week in advance.
(let [tx {:type :slot-reserved
          :tenant-name "Flo"
          :start-date (java.time.Instant/parse "2026-02-28T00:00:00Z")
          :end-date (java.time.Instant/parse "2026-03-01T00:00:00Z")}])

;; So assume we're at one week in advance. Now everyone gets a notification that Val wants to book the car. They can either confirm or object.
;; In fact, its probably enough that they are notified. That way they have the opportunity to object. If they don't use that, that's confirmation.
;; So after the specified waiting period, we just produce the "reservation-confirmed" event, assuming nobody objects.

;; How do we build the state from that?

;; And what should the state even look like?



(defn pending->confirmed [v]
  :confirmed)




(def dispatch-map {:slot-reserved (fn [state {:keys [id tenant-name start-date end-date] :as tx}]
                                    (-> state
                                        (assoc start-date :pending)
                                        (assoc end-date :pending)
                                        (update :pending-bookings assoc id tx)))
                   :reservation-confirmed (fn [state {:keys [id tenant-name reservation-id]}]
                                            (let [{:keys [start-date end-date] :as reservation} (get-in state [:pending-bookings reservation-id])]
                                              (-> state
                                                  (update start-date pending->confirmed))))})


(defn process-tx [state {:keys [type id] :as tx}]
  (let [processing-function (get dispatch-map type)]
    (processing-function state tx)))

(let [reservation-id (java.util.UUID/randomUUID)
      txs [{:xt/id  reservation-id
            :type :slot-reserved
            :tenant-name "Flo"
            :start-date (java.time.Instant/parse "2026-02-28T00:00:00Z")
            :end-date (java.time.Instant/parse "2026-03-01T00:00:00Z")}
           {:xt/id (java.util.UUID/randomUUID)
            :type :reservation-confirmed
            :tenant-name "Flo"
            :reservation-id reservation-id ;; TODO Is this a good idea?
            }]]
  ;; We could already try to reduce over txs here to get the state.
  ;; Is this too complex?
  ;; Or is it really simple?
  txs)


(comment

  (defonce node (xtn/start-node))

  ;; Submit a booking
  (xt/submit-tx node [[:put-docs :bookings {:xt/id 123  :notes "test"}]])

  ;; Query - always wrap in vec to consume lazy seq
  (vec (xt/q node '(from :bookings [*])))

  (xt/q node "SELECT * FROM bookings")

  ;; Stop node when done
  (.close node))

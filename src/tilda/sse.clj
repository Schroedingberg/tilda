(ns tilda.sse
  "Server-Sent Events (SSE) infrastructure for real-time updates.
   
   ## Overview
   
   This module provides a pub/sub mechanism for pushing live updates to
   connected browser clients using Server-Sent Events (SSE). It integrates
   with Datastar's patch-elements event format for seamless DOM updates.
   
   ## Architecture
   
   ```
   ┌─────────────┐      ┌─────────────┐      ┌─────────────┐
   │   Client    │ ──── │  http-kit   │ ──── │  channels   │
   │  (browser)  │ SSE  │   channel   │      │    atom     │
   └─────────────┘      └─────────────┘      └─────────────┘
                                                    │
                                                    │ broadcast!
                                                    ▼
   ┌─────────────┐      ┌─────────────┐      ┌─────────────┐
   │   Handler   │ ──── │  booking.clj│ ──── │   XTDB      │
   │  (routes)   │      │  (domain)   │      │   (data)    │
   └─────────────┘      └─────────────┘      └─────────────┘
   ```
   
   ## Usage
   
   1. Create an SSE endpoint using `sse-handler`:
   
      ```clojure
      (defn calendar-events [node]
        (sse/sse-handler))  ; Returns http-kit compatible handler
      ```
   
   2. Broadcast updates when data changes:
   
      ```clojure
      (sse/broadcast! \"<div id='day-2026-03-01'>...</div>\")
      ```
   
   3. Client connects via Datastar's data-init:
   
      ```html
      <div data-init=\"@get('/calendar/events', {openWhenHidden: true})\">
      ```
   
   ## Datastar Integration
   
   This module formats SSE events as Datastar `patch-elements` events:
   
   ```
   event: datastar-patch-elements
   data: elements <div id=\"target\">new content</div>
   ```
   
   Datastar automatically morphs the DOM elements with matching IDs.
   
   ## Implementation Notes
   
   - Uses http-kit's `as-channel` for native async support
   - Channels are stored in an atom and cleaned up on disconnect
   - Failed sends automatically unregister the channel
   - All operations are thread-safe"
  (:require
   [com.brunobonacci.mulog :as mu]
   [org.httpkit.server :as http-kit]))

;; =============================================================================
;; Channel Registry
;; =============================================================================

(defonce ^{:private true
           :doc "Set of active SSE channels. Protected by atom for thread safety."}
  channels (atom #{}))

(defn channel-count
  "Returns the number of currently connected SSE clients.
   Useful for monitoring and debugging."
  []
  (count @channels))

(defn- register-channel!
  "Add a channel to the active set. Called when client connects."
  [channel]
  (swap! channels conj channel)
  (mu/log ::sse-connected :clients (channel-count)))

(defn- unregister-channel!
  "Remove a channel from the active set. Called on disconnect or error."
  [channel]
  (swap! channels disj channel)
  (mu/log ::sse-disconnected :clients (channel-count)))

;; =============================================================================
;; SSE Message Formatting
;; =============================================================================

(defn format-datastar-fragment
  "Format HTML as a Datastar patch-elements SSE event.
   
   Datastar expects events in this format:
   
     event: datastar-patch-elements
     data: elements <html>
   
   The HTML should contain elements with IDs that Datastar will morph
   into the existing DOM.
   
   Example:
     (format-datastar-fragment \"<div id='day-2026-03-01'>...</div>\")
     => \"event: datastar-patch-elements\\ndata: elements <div>...\\n\\n\""
  [html]
  (str "event: datastar-patch-elements\n"
       "data: elements " html "\n\n"))

;; =============================================================================
;; Send & Broadcast
;; =============================================================================

(defn send!
  "Send an SSE message to a single channel.
   
   Returns true if send succeeded, false if it failed.
   Failed channels are automatically unregistered."
  [channel message]
  (try
    (http-kit/send! channel message false)  ; false = keep connection open
    true
    (catch Exception e
      (mu/log ::sse-send-error :error (.getMessage e))
      (unregister-channel! channel)
      false)))

(defn broadcast!
  "Send an HTML fragment to all connected SSE clients.
   
   The HTML is automatically wrapped in Datastar's patch-elements format.
   Failed sends are logged and the channel is cleaned up.
   
   Example:
     (broadcast! \"<div id='day-2026-03-01' class='booked'>1</div>\")"
  [html]
  (let [message (format-datastar-fragment html)]
    (doseq [channel @channels]
      (send! channel message))))

(defn broadcast-raw!
  "Send a raw SSE message (already formatted) to all clients.
   Use this when you need custom event types or formatting."
  [message]
  (doseq [channel @channels]
    (send! channel message)))

;; =============================================================================
;; HTTP Handler
;; =============================================================================

(defn sse-handler
  "Returns an http-kit handler for SSE connections.
   
   The handler upgrades the HTTP connection to SSE and registers the
   channel for broadcasts. Channels are automatically cleaned up when
   the client disconnects.
   
   Usage in routes:
     [\"/events\" {:get (sse/sse-handler)}]
   
   Or with custom on-connect logic:
     (sse/sse-handler {:on-connect (fn [ch] (send-initial-state ch))})"
  ([]
   (sse-handler {}))
  ([{:keys [on-connect]}]
   (fn [_request]
     (http-kit/as-channel
      _request
      {:on-open  (fn [channel]
                   (register-channel! channel)
                   (when on-connect
                     (on-connect channel)))
       :on-close (fn [channel _status]
                   (unregister-channel! channel))}))))

;; =============================================================================
;; Testing Utilities
;; =============================================================================

(defn reset-channels!
  "Clear all registered channels. For testing only."
  []
  (reset! channels #{})
  nil)

(ns tilda.auth
  "Swappable authentication middleware.
   
   Strategies configured via :auth in config.edn:
   
   {:auth {:strategy :dev}}              ; Stub user, no auth
   {:auth {:strategy :cloudflare}}       ; Cloudflare Access headers
   {:auth {:strategy :magic-link         ; Personal URL tokens
           :users {\"alice-xK9mP2\" {:name \"Alice\" :email \"alice@example.com\"}
                   \"bob-qR7nL4\"   {:name \"Bob\"   :email \"bob@example.com\"}}}}
   
   Magic links: share /u/alice-xK9mP2 → sets cookie → calendar access"
  (:require [clojure.string :as str]))

;; =============================================================================
;; Auth Strategies
;; =============================================================================

(def stub-user
  {:id    #uuid "00000000-0000-0000-0000-000000000001"
   :email "dev@tilda.local"
   :name  "Dev User"
   :roles #{:user}})

(defmulti extract-user
  "Extract user from request based on auth strategy."
  (fn [config _request] (:strategy config)))

(defmethod extract-user :dev
  [_ _request]
  stub-user)

(defmethod extract-user :cloudflare
  [_ request]
  (when-let [email (get-in request [:headers "cf-access-authenticated-user-email"])]
    {:id    (java.util.UUID/nameUUIDFromBytes (.getBytes email))
     :email email
     :name  (first (str/split email #"@"))
     :roles #{:user}}))

(defmethod extract-user :magic-link
  [{:keys [users]} request]
  ;; Check cookie first, then URL token
  (let [token (or (get-in request [:cookies "tilda-token" :value])
                  (get-in request [:path-params :token]))]
    (when-let [user-data (get users token)]
      (assoc user-data
             :id    (java.util.UUID/nameUUIDFromBytes (.getBytes (str token)))
             :token token
             :roles #{:user}))))

(defmethod extract-user :default
  [_ _]
  nil)

;; =============================================================================
;; Middleware
;; =============================================================================

(defn wrap-auth
  "Ring middleware that adds :user to request.
   
   Options:
   - :strategy - :dev, :cloudflare, or :magic-link
   - :users    - map of token->user for magic-link strategy
   - :public   - set of paths that don't require auth (e.g. #{\"/_/health\"})"
  [handler {:keys [strategy public] :or {strategy :dev public #{}} :as config}]
  (fn [request]
    (let [path (:uri request)
          user (extract-user (assoc config :strategy strategy) request)]
      (cond
        ;; Public paths - no auth needed
        (contains? public path)
        (handler request)

        ;; Magic link login - set cookie and redirect
        (and (= strategy :magic-link)
             (str/starts-with? path "/u/"))
        (let [token (subs path 3)]
          (if (extract-user (assoc config :strategy strategy)
                            (assoc-in request [:path-params :token] token))
            {:status  303
             :headers {"Location" "/calendar"
                       "Set-Cookie" (str "tilda-token=" token
                                         "; Path=/; HttpOnly; SameSite=Lax; Max-Age=31536000")}
             :body    ""}
            {:status 403 :body "Invalid link"}))

        ;; Authenticated - proceed
        user
        (handler (assoc request :user user))

        ;; Unauthenticated
        :else
        {:status 401
         :headers {"Content-Type" "text/plain"}
         :body "Authentication required"}))))

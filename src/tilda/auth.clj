(ns tilda.auth
  "Authentication middleware - stub implementation.
   
   Currently returns a hardcoded user for development.
   
   ## Swapping for Oak Auth
   
   To integrate with Oak (or another auth provider):
   
   1. Replace `stub-user` with a lookup function that validates tokens
   2. Update `extract-user` to parse the Authorization header:
      - Bearer tokens: (re-find #\"Bearer (.+)\" auth-header)
      - Session cookies: check ring session middleware
   3. Handle auth failures by returning 401 instead of using stub user
   
   Example Oak integration:
   
   ```clojure
   (defn extract-user [request]
     (when-let [token (-> request
                          (get-in [:headers \"authorization\"])
                          (some-> (str/replace #\"Bearer \" \"\")))]
       (oak/verify-token token)))  ; Returns user map or nil
   ```")

;; =============================================================================
;; Stub User (replace with real auth)
;; =============================================================================

(def stub-user
  "Hardcoded dev user. Replace with real auth lookup."
  {:id    #uuid "00000000-0000-0000-0000-000000000001"
   :email "dev@tilda.local"
   :name  "Dev User"
   :roles #{:user}})

;; =============================================================================
;; Middleware
;; =============================================================================

(defn extract-user
  "Extract user from request. Currently returns stub user.
   
   TODO: Check Authorization header or session for real auth."
  [_request]
  ;; Future: parse header, validate token, return user or nil
  stub-user)

(defn wrap-auth
  "Ring middleware that adds :user to the request map.
   
   The user is extracted via `extract-user`. If no user is found,
   the stub user is used (for dev). In production, return 401 instead."
  [handler]
  (fn [request]
    (let [user (extract-user request)]
      (handler (assoc request :user user)))))

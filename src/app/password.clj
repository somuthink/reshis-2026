(ns app.password
  (:require [buddy.core.codecs :as codecs]
            [buddy.hashers :as hashers]
            [clojure.string :as str]))

(def ^:private options
  {:alg :pbkdf2+sha256 :iterations 600000})

(def ^:private dummy-hash
  (delay (hashers/derive (str (random-uuid)) options)))

(defn password-hash
  [password]
  (hashers/derive password options))

(defn- buddy-hash [encoded]
  (if (str/starts-with? encoded "pbkdf2-sha256$")
    (let [[_ rounds salt hash & extra] (str/split encoded #"\$")
          salt (codecs/b64->bytes salt)
          hash (codecs/b64->bytes hash)]
      (when (and (nil? extra) (= "600000" rounds) (= 16 (alength salt))
                 (= 32 (alength hash)))
        (str "pbkdf2+sha256$" (codecs/bytes->hex salt) "$" rounds "$"
             (codecs/bytes->hex hash))))
    encoded))

(defn password-matches?
  [password encoded]
  (try
    (when-let [encoded (if (nil? encoded) @dummy-hash (buddy-hash encoded))]
      (:valid (hashers/verify password encoded {:limit #{:pbkdf2+sha256}})))
    (catch Exception _ false)))

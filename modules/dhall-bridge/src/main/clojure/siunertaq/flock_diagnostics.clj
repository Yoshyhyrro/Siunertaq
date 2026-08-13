(ns siunertaq.flock-diagnostics
  "Flock-size health diagnostics for leader-election viability.

   This is a from-scratch reimplementation of the *threshold model* that
   boidswarm (https://pypi.org/project/boidswarm/, GPL-3.0-only) publishes
   in its `zombiebubble check` static analyser (zombiebubble/cli.py, BW001-
   BW005). That model is a classical [n,k,d]_q linear-code parameterisation
   (code length n, dimension k, minimum distance d, alphabet size q), used
   here only to size-check a flock before leader election is attempted.

   IMPORTANT SCOPE NOTE: only this threshold model is publicly specified.
   boidswarm's actual leader-election algorithm, Gram-matrix personality
   scoring, and role-propagation logic ship inside a closed-source compiled
   library (zombiebubble.dll / .so) and are NOT reproduced here -- there is
   no public specification to port faithfully. Nothing in this namespace
   performs leader election; it only tells you whether a given flock size
   *could* support it.")

;; ==========================================
;; 1. Linear-code parameters
;; ==========================================
;; n = number of role classes (\"weight classes\")
;; k = independent signal dimensions required for election to be
;;     well-constrained
;; d = minimum distance of the correction graph
;; q = phase-group alphabet size
(def kernel-params
  {:n 7 :k 5 :d 3 :q 5})

(def error-correction-capacity
  "t = floor((d-1)/2): number of simultaneous leader losses the kernel
   can correct."
  (quot (dec (:d kernel-params)) 2))

(def erasure-capacity
  "d-1: number of simultaneous leader losses tolerated if losses are
   flagged (erasures) rather than silently corrected."
  (dec (:d kernel-params)))

(def erasure-margin
  "n + 2t: minimum flock size for full erasure-correction margin."
  (+ (:n kernel-params) (* 2 error-correction-capacity)))

;; ==========================================
;; 2. Diagnostics
;; ==========================================

(defn- primary-diagnostic
  "At most one primary diagnostic: the most severe threshold the flock
   size falls below (BW001 > BW002 > BW003, checked in that order)."
  [n-boids {:keys [n k d]}]
  (cond
    (< n-boids d)
    {:level :error :code "BW001"
     :message (str n-boids " boids < " d " (min-distance d): "
                   "error-correction structure collapsed")
     :help (str "the kernel corrects t=" error-correction-capacity
                " simultaneous leader loss but needs >= d=" d
                " boids to form the correction graph; add at least "
                (- d n-boids) " more")}

    (< n-boids k)
    {:level :warning :code "BW002"
     :message (str n-boids " boids < " k " (degrees of freedom k): "
                   "election underconstrained")
     :help (str "leader election needs k=" k " independent signal "
                "dimensions; the flock may lock into a degenerate "
                "low-rank configuration")}

    (< n-boids n)
    {:level :warning :code "BW003"
     :message (str n-boids " boids < " n " (weight classes n): "
                   "weight-class coverage incomplete")
     :help (str "the scoring kernel has n=" n " roles; with fewer boids "
                "the prime-leader role (highest class) may never be "
                "elected")}

    :else nil))

(defn- structural-complement-gap-diagnostic
  "BW004: N == n-1 (mod n) fills every role except the isolated w0
   complement, leaving the elected prime-leader without its counterpart."
  [n-boids {:keys [n d]}]
  (when (and (<= d n-boids) (< n-boids n)
             (= (mod n-boids n) (dec n)))
    {:level :note :code "BW004"
     :message (str "structural complement gap: " n-boids " = " (dec n)
                   " (mod " n ") - prime-leader may be elected with no "
                   "isolated complement (w0 absent)")
     :help (str "add 1 boid (-> " (inc n-boids) ") to close the "
                "complement pair")}))

(defn- phase-group-boundary-diagnostic
  "BW005: N == q-1 (mod q) falls on the phase-group parity boundary."
  [n-boids {:keys [n d q]}]
  (when (and (<= d n-boids) (< n-boids n)
             (= (mod n-boids q) (dec q)))
    {:level :note :code "BW005"
     :message (str "phase-group boundary: " n-boids " = " (dec q)
                   " (mod " q ") - weight assignment falls on the "
                   "parity-breaking depth level; complement-dual height "
                   "may not close")
     :help (str "add 1 boid (-> " (inc n-boids) ") to step off the "
                "phase boundary")}))

(defn- erasure-margin-diagnostic
  "Informational note: suggest the flock size needed for full
   erasure-correction margin (no code, matching the Python original)."
  [n-boids {:keys [d]}]
  (when (and (<= d n-boids) (< n-boids erasure-margin))
    {:level :note :code nil
     :message (str "for full erasure-correction margin (d-1="
                   erasure-capacity " simultaneous leader losses "
                   "tolerated), recommend >= " erasure-margin " boids")}))

(defn diagnose-flock-size
  "Returns a vector of diagnostic maps ({:level :code :message :help?})
   for a flock of n-boids, most-severe first. Returns [] when the flock
   size is fully healthy (no primary, structural, phase, or erasure-margin
   concerns)."
  ([n-boids] (diagnose-flock-size n-boids kernel-params))
  ([n-boids params]
   (->> [(primary-diagnostic n-boids params)
         (structural-complement-gap-diagnostic n-boids params)
         (phase-group-boundary-diagnostic n-boids params)
         (erasure-margin-diagnostic n-boids params)]
        (remove nil?)
        vec)))
(ns siunertaq.morton
  "Morton (Z-order curve) spatial indexing utilities.

   Extracted from what was previously test-only code so it can be reused
   directly by simulation/rendering code (e.g. a future game-engine
   module) without depending on the test namespace.")

(defn interleave-bits-3d
  "Interleaves 10-bit integer coordinates (x, y, z) into a 30-bit Morton
   Code. Maintains spatial locality for GPU Shader SSBO access.
   Bit assignment: x occupies bit 0 of each triplet, y bit 1, z bit 2
   (i.e. bit layout ...z2 y2 x2 z1 y1 x1 z0 y0 x0), matching the shifts
   below."
  [x y z]
  (letfn [(expand-bits [v]
            (let [v (bit-and v 0x000003ff)
                  v (bit-and (bit-or v (bit-shift-left v 16)) 0x030000ff)
                  v (bit-and (bit-or v (bit-shift-left v 8))  0x0300f00f)
                  v (bit-and (bit-or v (bit-shift-left v 4))  0x030c30c3)
                  v (bit-and (bit-or v (bit-shift-left v 2))  0x09249249)]
              v))]
    (bit-or (expand-bits x)
            (bit-shift-left (expand-bits y) 1)
            (bit-shift-left (expand-bits z) 2))))
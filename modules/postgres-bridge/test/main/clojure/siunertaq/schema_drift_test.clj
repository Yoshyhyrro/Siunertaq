(ns siunertaq.schema-drift-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [clojure.java.io :as io]))

;; ==========================================================================
;; clickhouse_schema.sql <-> ClickHouseSync.scala / MecrispCompiler.scala
;; column consistency check
;; ==========================================================================
;; forth_words and bytecode_instructions are populated via JSONEachRow
;; inserts built by flushWords()/BytecodeRow.toJson. Any DDL column absent
;; from that payload isn't a compile error or a runtime failure -- ClickHouse
;; (input_format_defaults_for_omitted_fields, on by default) just silently
;; fills the type's zero value: 0, [], "", or 32 zero bytes for
;; FixedString(32). For a column with an explicit DEFAULT (or a Nullable
;; type, where the fallback is NULL rather than a fabricated zero) that's a
;; defensible placeholder. For a column with neither, it's indistinguishable
;; from real data -- e.g. min_stack_depth's own comment calls it a "bug
;; indicator", but it silently reads 0 for every single row.
;;
;; This file reads the *actual current* SQL DDL and Scala source as text and
;; diffs their column sets, rather than hardcoding either side, because the
;; entire point is to catch the two ever drifting apart again -- a frozen
;; copy of either would defeat that.
;;
;; Run from modules/postgres-bridge (matches this project's other tests and
;; CI's working-directory), since paths below are relative to that root.

(defn- slurp-lines [path]
  (if-not (.exists (io/file path))
    (throw (ex-info (str "required source file not found: " path
                          " -- run tests from modules/postgres-bridge"
                     {:path path})))
    (str/split-lines (slurp path))))

(defn parse-ddl-table
  "Parses a `CREATE TABLE IF NOT EXISTS <table-name> ( ... )` block out of
   ClickHouse DDL source lines. Returns {column-name {:has-default? bool
   :materialized? bool :nullable? bool}}. Stops at the block's closing `)`,
   so trailing ENGINE/ORDER BY/SETTINGS clauses are never scanned as columns."
  [lines table-name]
  (let [start-re (re-pattern (str "CREATE TABLE IF NOT EXISTS\\s+" table-name "\\b"))
        start-idx (first (keep-indexed #(when (re-find start-re %2) %1) lines))]
    (when-not start-idx
      (throw (ex-info (str "table " table-name " not found in DDL -- has it "
                            "been renamed or removed?"
                       {:table table-name}))))
    (loop [idx (+ start-idx 2)                              ; skip name line + lone "("
           cols {}]
      (if (>= idx (count lines))
        (throw (ex-info (str "hit end of file before " table-name
                              "'s closing paren -- DDL format changed?"
                         {:table table-name})))
        (let [raw (nth lines idx)
              no-comment (first (str/split raw #"--" 2))
              trimmed (str/trim no-comment)]
          (cond
            (str/starts-with? (str/trim raw) ")") cols

            (str/blank? trimmed) (recur (inc idx) cols)

            :else
            (let [[col-name rest-str] (str/split trimmed #"\s+" 2)
                  col-name (str/replace col-name #"," "")
                  type-str (or rest-str "")]
              (if (re-matches #"[a-zA-Z_][a-zA-Z0-9_]*" col-name)
                (recur (inc idx)
                       (assoc cols col-name
                              {:has-default?  (boolean (re-find #"(?i)\bDEFAULT\b" type-str))
                               :materialized? (boolean (re-find #"(?i)\bMATERIALIZED\b" type-str))
                               :nullable?     (boolean (re-find #"(?i)Nullable\(" type-str))}))
                (recur (inc idx) cols)))))))))

(defn extract-json-keys
  "Extracts every `\"key_name\" ->` occurrence strictly between a
   start-marker line (inclusive) and the next line matching end-marker-re
   after it (exclusive). Both markers must be found, or this throws --
   silently returning an empty/partial key set on a marker miss would make
   the drift check pass vacuously instead of failing loudly."
  [lines start-marker-re end-marker-re]
  (let [start-idx (first (keep-indexed #(when (re-find start-marker-re %2) %1) lines))]
    (when-not start-idx
      (throw (ex-info "start marker not found -- has the function moved/renamed?"
                       {:marker (str start-marker-re)})))
    (let [rest-lines (drop start-idx lines)
          end-offset (first (keep-indexed
                              #(when (and (pos? %1) (re-find end-marker-re %2)) %1)
                              rest-lines))]
      (when-not end-offset
        (throw (ex-info "end marker not found after start marker"
                         {:marker (str end-marker-re)})))
      (set (keep #(second (re-find #"\"([a-zA-Z0-9_]+)\"\s*->" %))
                 (take end-offset rest-lines))))))

;; ---- Categorization ----

(defn hard-required-missing
  "DDL columns absent from the payload with neither a DEFAULT, a
   MATERIALIZED expression, nor a Nullable type -- omitting these means
   ClickHouse fabricates a zero value that is indistinguishable from real
   data. These are the genuine bugs."
  [ddl-cols payload-keys]
  (set (for [[col info] ddl-cols
             :when (and (not (payload-keys col))
                        (not (:has-default? info))
                        (not (:materialized? info))
                        (not (:nullable? info)))]
         col)))

(defn soft-missing
  "DDL columns absent from the payload that DO have a DEFAULT/MATERIALIZED
   expression or ARE Nullable -- a defensible placeholder (explicit
   default, or NULL meaning 'no data yet'), not silent corruption. Reported
   for visibility, not asserted as a failure."
  [ddl-cols payload-keys]
  (set (for [[col info] ddl-cols
             :when (and (not (payload-keys col))
                        (or (:has-default? info) (:materialized? info) (:nullable? info)))]
         col)))

;; ---- forth_words <-> ClickHouseSync.scala flushWords() ----

(def known-hard-missing-forth-words
  "literal_constants/min_stack_depth/instruction_count/class_file_hash:
   none have a DEFAULT, none are Nullable. min_stack_depth's own DDL
   comment calls it a 'bug indicator', but every row silently reads 0."
  #{"literal_constants" "min_stack_depth" "instruction_count" "class_file_hash"})

(def known-soft-missing-forth-words
  "has_infinite_loop has DEFAULT 0 -- an explicit, defensible 'not yet
   computed, assume false' placeholder."
  #{"has_infinite_loop"})

(deftest forth-words-schema-drift-test
  (testing "forth_words DDL columns vs. flushWords()'s JSON payload keys.
            Fails loudly (not silently) if the drift changes at all --
            grows, shrinks, or shifts between the hard/soft categories --
            since any of those mean the schema and the code disagree
            differently than currently understood."
    (let [sql-lines (slurp-lines "extension/clickhouse_schema.sql")
          scala-lines (slurp-lines "src/main/scala/io/siunertaq/postgres/ClickHouseSync.scala")
          ddl-cols (parse-ddl-table sql-lines "forth_words")
          payload-keys (extract-json-keys scala-lines
                                           #"private def flushWords"
                                           #"case Right\(_\)")]
      (is (= known-hard-missing-forth-words (hard-required-missing ddl-cols payload-keys)))
      (is (= known-soft-missing-forth-words (soft-missing ddl-cols payload-keys))))))

;; ---- bytecode_instructions <-> MecrispCompiler.scala BytecodeRow.toJson ----

(def known-hard-missing-bytecode-instructions
  "mecrisp_word_name is LowCardinality(String), not Nullable, no DEFAULT --
   omission silently reads as \"\", losing which instructions are Forth
   word boundaries."
  #{"mecrisp_word_name"})

(def known-soft-missing-bytecode-instructions
  "operand_str is Nullable (NULL is a legitimate 'no string operand for
   this opcode' value); is_unreferenced and ingested_at both have an
   explicit DEFAULT."
  #{"operand_str" "is_unreferenced" "ingested_at"})

(deftest bytecode-instructions-schema-drift-test
  (testing "bytecode_instructions DDL columns vs. BytecodeRow.toJson's
            payload keys. Same fail-loudly-on-any-change contract as the
            forth_words check above."
    (let [sql-lines (slurp-lines "extension/clickhouse_schema.sql")
          scala-lines (slurp-lines "src/main/scala/io/siunertaq/postgres/MecrispCompiler.scala")
          ddl-cols (parse-ddl-table sql-lines "bytecode_instructions")
          payload-keys (extract-json-keys scala-lines
                                           #"def toJson: Json = Json\.obj"
                                           #"Produce per-instruction rows")]
      (is (= known-hard-missing-bytecode-instructions
             (hard-required-missing ddl-cols payload-keys)))
      (is (= known-soft-missing-bytecode-instructions
             (soft-missing ddl-cols payload-keys))))))
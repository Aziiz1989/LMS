(ns lms.pdf
  "PDF generation using Typst.

   This namespace provides on-demand PDF generation from document snapshots.
   PDFs are generated dynamically when requested, not pre-generated or stored.

   Flow:
   1. Extract EDN snapshot from document entity
   2. Convert EDN → JSON (Typst's native data format)
   3. Write JSON to temp file
   4. Invoke Typst CLI to compile template with data
   5. Read PDF bytes and return
   6. Clean up temp files"
  (:require [clojure.java.shell :as shell]
            [clojure.java.io :as io]
            [jsonista.core :as json]
            [clojure.edn :as edn]
            [taoensso.timbre :as log]))

(defn get-template-path
  "Get file path for Typst template based on document type.

   Args:
   - document-type: :clearance-letter | :statement | :contract-agreement

   Returns: String path to .typ template file
   Throws: ex-info if document type is unknown"
  [document-type]
  (case document-type
    :clearance-letter "resources/templates/clearance-letter.typ"
    :statement "resources/templates/statement.typ"
    :contract-agreement "resources/templates/contract-agreement.typ"
    (throw (ex-info "Unknown document type" {:type document-type}))))

(defn snapshot->json
  "Convert EDN snapshot string to JSON for Typst consumption.

   Args:
   - snapshot-edn: String containing EDN data

   Returns: String containing JSON data"
  [snapshot-edn]
  (let [data (edn/read-string snapshot-edn)]
    (json/write-value-as-string data)))

(defn render-typst-to-pdf
  "Compile Typst template to PDF with data.

   Creates temp files for JSON data and PDF output, invokes Typst CLI,
   reads PDF bytes, and cleans up temp files.

   Args:
   - template-path: Path to .typ file
   - data-json: JSON string with document data

   Returns: {:success? true :pdf-bytes byte-array} or {:success? false :error string}"
  [template-path data-json]
  ;; Use a fixed filename for the JSON data file in the template directory
  (let [template-dir (-> template-path io/file .getParentFile)
        data-file (io/file template-dir "data.json")
        temp-pdf (java.io.File/createTempFile "typst-output-" ".pdf")]
    (try
      ;; Write JSON data to fixed filename
      (spit data-file data-json)

      ;; Verify file was written
      (log/debug "Wrote data file" {:path (.getAbsolutePath data-file)
                                     :exists? (.exists data-file)
                                     :size (.length data-file)})

      (log/debug "Invoking Typst" {:template template-path
                                    :data-file (.getAbsolutePath data-file)
                                    :output-file (.getAbsolutePath temp-pdf)})

      ;; Invoke Typst CLI
      (let [abs-template-path (.getAbsolutePath (io/file template-path))
            abs-pdf-path (.getAbsolutePath temp-pdf)
            {:keys [exit out err]}
            (shell/sh "typst" "compile"
                      abs-template-path
                      abs-pdf-path)]
        (if (zero? exit)
          ;; Success - read PDF bytes
          (let [pdf-bytes (with-open [in (io/input-stream temp-pdf)]
                           (let [buffer (byte-array (.length temp-pdf))]
                             (.read in buffer)
                             buffer))]
            (log/debug "Typst compilation successful" {:pdf-size (alength pdf-bytes)})
            {:success? true :pdf-bytes pdf-bytes})

          ;; Failure - return error
          (do
            (log/error "Typst compilation failed" {:exit exit :stderr err :stdout out})
            {:success? false :error (str "Typst compilation failed: " err)})))

      (finally
        ;; Clean up temp files
        (.delete data-file)
        (.delete temp-pdf)))))

(defn generate-pdf
  "Generate PDF for a document snapshot.

   High-level function that orchestrates the PDF generation process.

   Args:
   - document-type: :clearance-letter | :statement | :contract-agreement
   - snapshot-edn: EDN string from document entity

   Returns: {:success? true :pdf-bytes byte-array} or {:success? false :error string}"
  [document-type snapshot-edn]
  (try
    (log/info "Generating PDF" {:document-type document-type})
    (let [template-path (get-template-path document-type)
          data-json (snapshot->json snapshot-edn)]
      (render-typst-to-pdf template-path data-json))
    (catch Exception e
      (log/error e "PDF generation failed" {:document-type document-type})
      {:success? false :error (.getMessage e)})))

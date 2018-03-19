(ns co.deps.ring-etag-middleware
  (:require [clojure.java.io :as io]
            [ring.util.response :as response])
  (:import (java.util.zip CRC32 Adler32)
           (java.io File)
           (java.nio.file Path Files LinkOption FileSystemException)
           (java.nio.file.attribute UserDefinedFileAttributeView)
           (java.nio ByteBuffer)
           (java.nio.charset Charset)))



#_(ns pandect.algo.crc32
    "CRC-32 algorithm implementation"
    (:require
      [pandect.utils.convert])
    (:import (java.util.zip CRC32)))

;
;(defprotocol compute-crc32-protocol
;  (compute-crc32 [x]))
;
;(doseq
;  [v__186__auto__ [#'compute-crc32-protocol #'compute-crc32]]
;  (alter-meta!
;    v__186__auto__ assoc
;    :private true))
;(extend-protocol
;  compute-crc32-protocol
;  (class (byte-array 0))
;  (compute-crc32
;    [data1166]
;    (let [buf__898__auto__ (bytes data1166)
;          a__899__auto__ (new CRC32)]
;      (.update a__899__auto__ buf__898__auto__ 0 (alength buf__898__auto__))
;      (.getValue a__899__auto__)))
;  java.lang.String
;  (compute-crc32
;    [data1166]
;    (let
;      [data1166 (.getBytes data1166 "UTF-8")]
;      (let [buf (bytes data1166)
;            a (new CRC32)]
;        (.update a buf 0 (alength buf))
;        (.getValue a)))))
;(extend-protocol
;  compute-crc32-protocol
;  java.io.InputStream
;  (compute-crc321164
;    [is]
;    (let
;      [s is
;       buffer-size (int *buffer-size*)
;       buf (byte-array buffer-size)
;       crc-32 (new CRC32)]
;      (loop
;        []
;        (let
;          [r__904__auto__
;           (.read s buf 0 buffer-size)]
;          (when-not
;            (= r__904__auto__ -1)
;            (.update crc-32 buf 0 r__904__auto__)
;            (recur))))
;      (.getValue crc-32)))
;  java.io.File
;  (compute-crc321164
;    [data1166]
;    (with-open
;      [data1166 (clojure.java.io/input-stream data1166)]
;      (let
;        [s__900__auto__
;         data1166
;         c__901__auto__
;         (int *buffer-size*)
;         buf__902__auto__
;         (byte-array c__901__auto__)
;         a__903__auto__
;         (new CRC32)]
;        (loop
;          []
;          (let
;            [r__904__auto__
;             (.read s__900__auto__ buf__902__auto__ 0 c__901__auto__)]
;            (when-not
;              (= r__904__auto__ -1)
;              (.update a__903__auto__ buf__902__auto__ 0 r__904__auto__)
;              (recur))))
;        (.getValue a__903__auto__)))))
;'compute-crc32-protocol
;
;
;(defn
;  crc32*
;  "[Hash] CRC-32 (raw value)"
;  [x]
;  (compute-crc32 x))
;(defn
;  crc32-file*
;  "[Hash] CRC-32 (raw value)"
;  [x]
;  (with-open
;    [x (clojure.java.io/input-stream (clojure.java.io/file x))]
;    (compute-crc32 x)))
;(defn
;  crc32-bytes
;  "[Hash] CRC-32 (value -> byte array)"
;  [x]
;  (pandect.utils.convert/long->4-bytes (compute-crc32 x)))
;(defn
;  crc32-file-bytes
;  "[Hash] CRC-32 (file path -> byte array)"
;  [x]
;  (with-open
;    [x (clojure.java.io/input-stream (clojure.java.io/file x))]
;    (pandect.utils.convert/long->4-bytes (compute-crc32 x))))
;(defn
;  crc32
;  "[Hash] CRC-32 (value -> string)"
;  [x]
;  (pandect.utils.convert/long->hex (compute-crc32 x)))
;(defn
;  crc32-file
;  "[Hash] CRC-32 (file path -> string)"
;  [x]
;  (with-open
;    [x (clojure.java.io/input-stream (clojure.java.io/file x))]
;    (pandect.utils.convert/long->hex (compute-crc32 x))))


;;;;





(defn checksum-file
  "Calculate an Adler 32 checksum for a File."
  ;; Copied from code generated by Pandect
  ;; https:// github.com / xsc/pandect
  [^File file]
  (with-open [is (io/input-stream file)]
    (let [buffer-size (int 2048)
          ba (byte-array buffer-size)
          adler-32 (new Adler32)]
      (loop []
        (let [num-bytes-read (.read is ba 0 buffer-size)]
          (when-not (= num-bytes-read -1)
            (.update adler-32 ba 0 num-bytes-read)
            (recur))))
      (.getValue adler-32))))

(defn ^UserDefinedFileAttributeView get-user-defined-attribute-view [path]
  (Files/getFileAttributeView
    path
    UserDefinedFileAttributeView
    (into-array LinkOption [])))

(def checksum-attribute-name "user.ring-etag-middleware.adler32-checksum")

(defn get-attribute [path attribute]
  (try
    (let [view (get-user-defined-attribute-view path)
          name attribute
          size (.size view name)
          attr-buf (ByteBuffer/allocate size)]
      (.read view name attr-buf)
      (.flip attr-buf)
      (str (.decode (Charset/defaultCharset) attr-buf)))
    (catch FileSystemException e
      nil)))

(defn set-attribute [path attribute ^String value]
  (let [view (get-user-defined-attribute-view path)]
    (.write view attribute (.encode (Charset/defaultCharset) value))))

;; Public

(defn supports-extended-attributes?
  "Not all filesystems suport Java's UserDefinedFileAttributes (a.k.a. extended attributes),
  notably HFS+ and APFS on macOS.

  Waiting for https://bugs.openjdk.java.net/browse/JDK-8030048 to add macOS support."
  [^Path path]
  (.supportsFileAttributeView
    (Files/getFileStore path)
    ^Class UserDefinedFileAttributeView))

(defn add-file-etag
  [response extended-attributes?]
  (let [file (:body response)]
    (if (instance? File file)
      (let [path (.toPath ^File file)]
        (if extended-attributes?
          (if-let [checksum (get-attribute path checksum-attribute-name)]
            (response/header response "ETag" checksum)
            (let [checksum (checksum-file file)]
              (set-attribute path checksum-attribute-name (str checksum))
              (response/header response "ETag" checksum)))
          (response/header response "ETag" (checksum-file file))))
      response)))

(defn wrap-file-etag
  "Calculates an ETag for a Ring response which contains a File as the body.

  If extended-attributes? is true, then the File is first checked for a
  checksum in it's extended attributes, if it doesn't exist then it is
  calculated and added to the file, and returned in the ETag. This is
  much faster than calculating the checksum each time (which is already
  fast), but isn't supported on all platforms, notably macOS.

  If you wish to store the checksum in extended attributes, it is
  recommended that you first check if the Path that you are wanting
  to serve files from supports it. You can use the provided
  supports-extended-attributes? function for this."
  ([handler]
    (wrap-file-etag handler {}))
  ([handler {:keys [extended-attributes?] :as options}]
   (fn [req]
     (add-file-etag (handler req) extended-attributes?))))

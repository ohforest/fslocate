(ns thornydev.fslocate.core
  (:refer-clojure :exclude [peek take])
  (:require [clojure.core :as clj]
            [clojure.java.jdbc :as jdbc]
            [clojure.string :as str]
            [clojure.java.io :as io]
            [clojure.set :refer [difference]]
            [thornydev.go-lightly.core :refer :all])
  (:import (java.util.concurrent CountDownLatch))
  (:gen-class))

;; TODO: once working, replace "file fns" with the https://github.com/Raynes/fs library

(def ^:dynamic *db-spec* {:classname "org.sqlite.JDBC"
                          :subprotocol "sqlite"
                          :subname "db/fsupdate.db"})

(def ^:dynamic *nindexers* 4)

(def query-ch (channel 5000))
(def delete-ch (channel 5000))
(def insert-ch (channel 5000))

;; ---[ DESIGN ]--- ;;
;; 3 threads (as go-lightly routines)
;; 1. indexer: searches the existing data files

;; indexer thread -> one or more
;;  reads conf file to start on fs
;;  grabs all files from that dir and queries db to get all recorded files from that dir
;;  compares: deletes those not present anymore, adds those newly present
;;  => but doesn't add/delete directly. pushes onto a queue or messages the dbupdater thread/routine
;; can have multiple indexer threads
;; possible race condition with dbupdater thread -> may want to have global db lock in memory so
;;  only one thread is reading/writing at a time?

;; dbupdater thread => ONLY ONE
;;  reads from queue of SQL inserts/deletes/updates that get added by indexer threads
;;  performs updates on the db directly

;; resource contention
;;  => indexer threads periodically sleep for 1 minute?
;;     only runs twice per day?

(defn prf [& vals]
  (let [s (apply str (interpose " " (map #(if (nil? %) "nil" %) vals)))]
    (print (str s "\n")) (flush)
    s))

(def latch (CountDownLatch. *nindexers*))

(defn read-conf []
  (str/split-lines (slurp "conf/fslocate.conf")))

;; ---[ database fns ]--- ;;

(defn dbdelete
  "fname: string of full path for file/dir"
  [recordset]
  (doseq [r recordset]
    (jdbc/delete-rows :files ["PATH = ? and TYPE = ?" (:path r) (:type r)])))

(defn dbinsert
  "recordset: set of records of form: {:type f|d :path abs-path}
  must be called within a with-connection wrapper"
  [recordset]
  (apply jdbc/insert-records :files recordset))

(defn dbquery
  "dirpath: abs-path to a directory
  must be called within a with-connection wrapper"
  [{:keys [dirpath reply-ch]}]
  (put reply-ch
       (if-let [origdir-rt (jdbc/with-query-results res
                             ["SELECT path, type FROM files WHERE path = ?" dirpath]
                             (doall res))]
         (flatten
          (cons origdir-rt
                (jdbc/with-query-results res
                  ["SELECT path, type FROM files WHERE type = ? AND path LIKE ? AND path NOT LIKE ?"
                   "f" (str dirpath "/%") (str dirpath "/%/%")]
                  (doall res))))
         false)))

(defn dbhandler []
  (jdbc/with-connection *db-spec*
    ;; TODO: need an atom to check state against for sleeping/pausing/shutting down
    (loop []
      (selectf query-ch  #(dbquery %)
               insert-ch #(dbinsert %)
               delete-ch #(dbdelete %)
               (timeout-channel 2000) #(identity %))
      (recur))))

(defn partition-results
  "records should be of form: {:path /usr/local/bin, :type d}
  fs-recs: seq of file system records
  dbrecs: EITHER: seq of records from db query, OR: a boolean false (meaning the database
          had no records of this directory and its files
  @return: vector pair: [set of records only on the fs, set of records only in the db]"
  [fs-recs db-recs]
  (if db-recs
    (let [fs-set (set fs-recs)
          db-set (set db-recs)]
      [(difference fs-set db-set) (difference db-set fs-set)])
    [(set fs-recs) #{}]))

(defn sync-list-with-db
  "topdir: (string): directory holding the +files+
  files: (seq/coll of strings): files to sync with the db"
  [topdir files]
  (let [reply-ch (channel)
        _        (put query-ch {:dirpath topdir :reply-ch reply-ch})
        db-recs  (take reply-ch)
        fs-recs  (cons {:path topdir :type "d"} (map #(array-map :path % :type "f") files))
        [fsonly dbonly]  (partition-results fs-recs db-recs)]
    (put insert-ch fsonly)
    (put delete-ch dbonly)))

(defn list-dir
  "List files and directories under path."
  [^String path]
  (map #(str path "/" %) (seq (.list (io/file path)))))

(defn file?
  "Return true if path is a file."
  [path]
  (.isFile (io/file path)))

(defn indexer
  "coll/seq of dirs (as strings) to index with the fslocate db"
  [search-dirs]
  (loop [dirs search-dirs]
    (prf "Doing" (first dirs))
    (if-not (seq dirs)
      (.countDown latch)
      (let [[files subdirs] (->> (first dirs)
                                 list-dir
                                 (partition-bifurcate file?))]
        (sync-list-with-db (first dirs) files)
        (recur (concat (rest dirs) subdirs))))))

(defn -main [& args]
  (let [vdirs (read-conf)
        parts (vec (partition-all (/ (count vdirs) *nindexers*) vdirs))]
    (gox (dbhandler))
    (doseq [p parts]
      (gox (indexer (vec p))))
    )
  (.await latch)
  (Thread/sleep 500)
  (stop)
  (shutdown)
  )

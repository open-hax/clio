(ns clio.infra.host-fixture
  (:require [clio.law.schema :as schema-law]))

(def catalog
  {:record/observed
   (schema-law/event-schema
    :record/observed
    [:map {:closed true} [:record/id :uuid] [:observed/at 'inst?] [:amount :int]])})

(def payload
  {:record/id #uuid "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee"
   :observed/at #inst "2026-09-11T01:02:03.004Z"
   :amount 7})

(def facts
  {:event/stream "record:a"
   :event/seq 1
   :event/actor "test:writer"
   :event/subject "record:a"
   :event/data payload})

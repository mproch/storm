;; Licensed to the Apache Software Foundation (ASF) under one
;; or more contributor license agreements.  See the NOTICE file
;; distributed with this work for additional information
;; regarding copyright ownership.  The ASF licenses this file
;; to you under the Apache License, Version 2.0 (the
;; "License"); you may not use this file except in compliance
;; with the License.  You may obtain a copy of the License at
;;
;; http://www.apache.org/licenses/LICENSE-2.0
;;
;; Unless required by applicable law or agreed to in writing, software
;; distributed under the License is distributed on an "AS IS" BASIS,
;; WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
;; See the License for the specific language governing permissions and
;; limitations under the License.
(ns org.apache.storm.drpc-test
  (:use [clojure test])
  (:import [org.apache.storm.drpc ReturnResults DRPCSpout
            LinearDRPCTopologyBuilder]
           [org.apache.storm.utils ConfigUtils Utils])
  (:import [org.apache.storm.topology FailedException])
  (:import [org.apache.storm.coordination CoordinatedBolt$FinishedCallback])
  (:import [org.apache.storm LocalDRPC LocalCluster])
  (:import [org.apache.storm.tuple Fields])
  (:import [org.apache.storm.utils ConfigUtils]
           [org.apache.storm.utils.staticmocking ConfigUtilsInstaller])
  (:import [org.apache.storm.generated DRPCExecutionException])
  (:import [java.util.concurrent ConcurrentLinkedQueue])
  (:import [org.apache.storm Thrift])
  (:use [org.apache.storm config testing])
  (:use [org.apache.storm.internal clojure])
  (:use [org.apache.storm.daemon common drpc])
  (:use [conjure core]))

(defbolt exclamation-bolt ["result" "return-info"] [tuple collector]
  (emit-bolt! collector
              [(str (.getString tuple 0) "!!!") (.getValue tuple 1)]
              :anchor tuple)
  (ack! collector tuple)
  )

(deftest test-drpc-flow
  (let [drpc (LocalDRPC.)
        spout (DRPCSpout. "test" drpc)
        cluster (LocalCluster.)
        topology (Thrift/buildTopology
                  {"1" (Thrift/prepareSpoutDetails spout)}
                  {"2" (Thrift/prepareBoltDetails
                         {(Utils/getGlobalStreamId "1" nil)
                          (Thrift/prepareShuffleGrouping)}
                         exclamation-bolt)
                   "3" (Thrift/prepareBoltDetails
                         {(Utils/getGlobalStreamId "2" nil)
                          (Thrift/prepareGlobalGrouping)}
                         (ReturnResults.))})]
    (.submitTopology cluster "test" {} topology)

    (is (= "aaa!!!" (.execute drpc "test" "aaa")))
    (is (= "b!!!" (.execute drpc "test" "b")))
    (is (= "c!!!" (.execute drpc "test" "c")))
    
    
    (.shutdown cluster)
    (.shutdown drpc)
    ))

(defbolt exclamation-bolt-drpc ["id" "result"] [tuple collector]
  (emit-bolt! collector
              [(.getValue tuple 0) (str (.getString tuple 1) "!!!")]
              :anchor tuple)
  (ack! collector tuple)
  )

(deftest test-drpc-builder
  (let [drpc (LocalDRPC.)
        cluster (LocalCluster.)
        builder (LinearDRPCTopologyBuilder. "test")
        ]
    (.addBolt builder exclamation-bolt-drpc 3)
    (.submitTopology cluster
                     "builder-test"
                     {}
                     (.createLocalTopology builder drpc))
    (is (= "aaa!!!" (.execute drpc "test" "aaa")))
    (is (= "b!!!" (.execute drpc "test" "b")))
    (is (= "c!!!" (.execute drpc "test" "c")))  
    
    (.shutdown cluster)
    (.shutdown drpc)
    ))

(defn safe-inc [v]
  (if v (inc v) 1))

(defbolt partial-count ["request" "count"] {:prepare true}
  [conf context collector]
  (let [counts (atom {})]
    (bolt
     (execute [tuple]
              (let [id (.getValue tuple 0)]
                (swap! counts update-in [id] safe-inc)
                (ack! collector tuple)
                ))
     CoordinatedBolt$FinishedCallback
     (finishedId [this id]
                 (emit-bolt! collector [id (get @counts id 0)])
                 ))
    ))

(defn safe+ [v1 v2]
  (if v1 (+ v1 v2) v2))

(defbolt count-aggregator ["request" "total"] {:prepare true}
  [conf context collector]
  (let [counts (atom {})]
    (bolt
     (execute [tuple]
              (let [id (.getValue tuple 0)
                    count (.getValue tuple 1)]
                (swap! counts update-in [id] safe+ count)
                (ack! collector tuple)
                ))
     CoordinatedBolt$FinishedCallback
     (finishedId [this id]
                 (emit-bolt! collector [id (get @counts id 0)])
                 ))
    ))

(defbolt create-tuples ["request"] [tuple collector]
  (let [id (.getValue tuple 0)
        amt (Integer/parseInt (.getValue tuple 1))]
    (doseq [i (range (* amt amt))]
      (emit-bolt! collector [id] :anchor tuple))
    (ack! collector tuple)
    ))

(deftest test-drpc-coordination
  (let [drpc (LocalDRPC.)
        cluster (LocalCluster.)
        builder (LinearDRPCTopologyBuilder. "square")
        ]
    (.addBolt builder create-tuples 3)
    (doto (.addBolt builder partial-count 3)
      (.shuffleGrouping))
    (doto (.addBolt builder count-aggregator 3)
      (.fieldsGrouping (Fields. ["request"])))

    (.submitTopology cluster
                     "squared"
                     {}
                     (.createLocalTopology builder drpc))
    (is (= "4" (.execute drpc "square" "2")))
    (is (= "100" (.execute drpc "square" "10")))
    (is (= "1" (.execute drpc "square" "1")))
    (is (= "0" (.execute drpc "square" "0")))
    
    
    (.shutdown cluster)
    (.shutdown drpc)
    ))

(defbolt id-bolt ["request" "val"] [tuple collector]
  (emit-bolt! collector
              (.getValues tuple)
              :anchor tuple)
  (ack! collector tuple))

(defbolt emit-finish ["request" "result"] {:prepare true}
  [conf context collector]
  (bolt
   (execute [tuple]
            (ack! collector tuple)
            )
   CoordinatedBolt$FinishedCallback
   (finishedId [this id]
               (emit-bolt! collector [id "done"])
               )))

(deftest test-drpc-coordination-tricky
  (let [drpc (LocalDRPC.)
        cluster (LocalCluster.)
        builder (LinearDRPCTopologyBuilder. "tricky")
        ]
    (.addBolt builder id-bolt 3)
    (doto (.addBolt builder id-bolt 3)
      (.shuffleGrouping))
    (doto (.addBolt builder emit-finish 3)
      (.fieldsGrouping (Fields. ["request"])))

    (.submitTopology cluster
                     "tricky"
                     {}
                     (.createLocalTopology builder drpc))
    (is (= "done" (.execute drpc "tricky" "2")))
    (is (= "done" (.execute drpc "tricky" "3")))
    (is (= "done" (.execute drpc "tricky" "4")))
    (.shutdown cluster)
    (.shutdown drpc)
    ))

(defbolt fail-finish-bolt ["request" "result"] {:prepare true}
  [conf context collector]
  (bolt
   (execute [tuple]
            (ack! collector tuple))
   CoordinatedBolt$FinishedCallback
   (finishedId [this id]
               (throw (FailedException.))
               )))

(deftest test-drpc-fail-finish
  (let [drpc (LocalDRPC.)
        cluster (LocalCluster.)
        builder (LinearDRPCTopologyBuilder. "fail2")
        ]
    (.addBolt builder fail-finish-bolt 3)

    (.submitTopology cluster
                     "fail2"
                     {}
                     (.createLocalTopology builder drpc))
    
    (is (thrown? DRPCExecutionException (.execute drpc "fail2" "2")))

    (.shutdown cluster)
    (.shutdown drpc)
    ))

(deftest test-dequeue-req-after-timeout
  (let [queue (ConcurrentLinkedQueue.)
        delay-seconds 2
        conf {DRPC-REQUEST-TIMEOUT-SECS delay-seconds}
        mock-cu (proxy [ConfigUtils] []
                  (readStormConfigImpl [] conf))]
    (with-open [_ (ConfigUtilsInstaller. mock-cu)]
      (stubbing [acquire-queue queue]
        (let [drpc-handler (service-handler conf)]
          (is (thrown? DRPCExecutionException
            (.execute drpc-handler "ArbitraryDRPCFunctionName" "")))
          (is (= 0 (.size queue))))))))

(deftest test-drpc-timeout-cleanup 
  (let [queue (ConcurrentLinkedQueue.)
        delay-seconds 1
        conf {DRPC-REQUEST-TIMEOUT-SECS delay-seconds}
        mock-cu (proxy [ConfigUtils] []
                  (readStormConfigImpl [] conf))]
    (with-open [_ (ConfigUtilsInstaller. mock-cu)]
          (stubbing [acquire-queue queue
                     timeout-check-secs delay-seconds]
              (let [drpc-handler (service-handler conf)]
                (is (thrown? DRPCExecutionException 
                             (.execute drpc-handler "ArbitraryDRPCFunctionName" "no-args"))))))))


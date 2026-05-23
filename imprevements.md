Re-run PH scaling for transition zone (1M, 2M, 5M, 10M, 20M, 50M) with high point-repetitions to clean up bimodal artifact





`cd /Users/macsia/projects/testProjects/db-algo-lab1 && rm -f build/results/lookup_scaling_smoke_v2.json && ls build/results/lookup_scaling_ph_log_1k_50m_median5.json && echo "Rerun started at: $(date '+%H:%M:%S')"`

```
build/results/lookup_scaling_ph_log_1k_50m_median5.json
Rerun started at: 00:28:37
```

`cd /Users/macsia/projects/testProjects/db-algo-lab1 && nohup ./gradlew lookupScaling --no-daemon -q -PlookupScalingSeries=perfecthash -PlookupScalingDataSizes=1000000,2000000 -PlookupScalingPointRepetitions=10 -PlookupScalingMaxHeap=12g -PlookupScalingOutput=build/results/lookup_scaling_ph_1m_2m_median10.json > /tmp/ph_rerun_1m2m.log 2>&1 &`  
`echo "Started PID: $!"`  
`echo "Started at: $(date '+%H:%M:%S')"`

```
Started PID: 51732
Started at: 00:28:43
```


Re-run PH scaling for transition zone (1M, 2M, 5M, 10M, 20M, 50M) with high point-repetitions to clean up bimodal artifact

## Verify pipeline (~5–15 min lab3 light, ~1 min lab1 light)

```bash
cd /home/macsia/Projects/git/db-algo-lab1 && ./run_light.sh
cd /home/macsia/Projects/git/db-algo-lab3 && ./run_light.sh
```

## Full chain (lab1 then lab3)

```bash
cd /home/macsia/Projects/git/db-algo-lab1 && ./run_all.sh && \
cd /home/macsia/Projects/git/db-algo-lab3 && ./run_all.sh
```

Background:

```bash
nohup bash -c 'cd /home/macsia/Projects/git/db-algo-lab1 && ./run_all.sh && cd /home/macsia/Projects/git/db-algo-lab3 && ./run_all.sh' \
  > /tmp/labs_1_3.log 2>&1 &
echo "PID: $!"
```

## lab1 `./run_all.sh`

Single gradle chain: PH transition zone `1M..50M`, `pointRepetitions=10`, `-Xmx32g` → `build/results/lookup_scaling_ph_transition_median10.json`, then `gen_charts.py`.

Override: `PH_SIZES=1000000,2000000 PH_POINT_REPS=10 ./run_all.sh`

## Gradle / Java note

System Java 26 requires **Gradle 9.4+** (wrapper updated). `./run_light.sh` must pass before starting `./run_all.sh`.

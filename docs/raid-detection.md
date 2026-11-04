# Automatic raid detection

NUSH monitors joins, chat messages, and player commands while quarantine is on or off.
There are no raid settings to tune. Quarantine duration, recent joins, logging, and the staff feed have their own settings.

## Operation

- NUSH counts events from connected players who have neither staff trust nor `plex.nush.bypass`.
- Count each join event, including reconnects. Count each chat message and command attempt, including cancelled events.
- NUSH counts commands before the command-name and WorldEdit checks. A denied `/kill` still contributes to detection.
- Skip join detection for the first 60 seconds after module enablement. Do not use these startup joins for training.
- Monitor chat and commands immediately. There is no startup grace for these signals.
- Enable quarantine as soon as any signal reaches its adaptive limit. Apply the existing recent-join and exemption policy.
- Continue counting during quarantine. A disconnect does not erase events from the traffic windows.
- Keep the pre-raid baseline during every wave and the quiet period between waves.
- Report quiet traffic only after all signals stay below their recovery limits for 120 seconds.
- Keep NUSH enabled after the quiet notice. Quiet traffic is evidence of reduced activity, not proof that the attacker left.

Use `/nush status` to see whether the detector is monitoring, in startup join grace, tracking a raid, or stopped.
Use `/nush off` to disable quarantine manually. Monitoring continues; another spike can enable it again.
Automatic activation changes runtime state only. After module reload or server restart, NUSH uses `server.enabled`
and starts a fresh baseline and startup grace. Use `/nush on` to save an enabled startup state.

Detection does not change command-blocking policy. NUSH still uses `block_on_mute` and its WorldEdit restrictions.
It does not automatically block every command that contributes to a spike.

## Baseline and limits

Each signal has a short window of one-second count buckets. NUSH compares the current window with historical
windows of the same duration. The current bucket is partial; detection has one-second time resolution.

| Signal | Window | Minimum activation count |
| --- | ---: | ---: |
| Joins | 10 seconds | 10 |
| Chat messages | 5 seconds | 30 |
| Commands | 2 seconds | 40 |

These internal minimums prevent a few events after an idle period from triggering quarantine. They are not the
normal-traffic baseline. The adaptive limit increases when clean traffic is higher.

Once per second, NUSH samples the last complete window. It holds the sample for 60 seconds before adding it to the
baseline. On raid detection, it discards all waiting samples across all three signals. Thus, the current spike and
its recent lead-up cannot immediately raise the baseline used to detect that spike.

NUSH retains the last 300 accepted samples per signal, including zero-traffic samples. With an uninterrupted timer,
these represent five minutes of clean window samples. During a raid, NUSH preserves them rather than replacing them
with raid traffic or zeros. After the quiet notice, it resumes delayed sampling without clearing the saved history.

For each signal:

```text
b = median of accepted window counts
MAD = median of abs(count - b)
s = max(1, sqrt(b), 1.4826 * MAD)
activation limit = ceil(max(minimum, 5*b, b + 6*s))
recovery limit   = ceil(max(minimum/2, 2*b, b + 3*s))
```

The median and median absolute deviation reduce the effect of isolated outliers. See the
[NIST description of robust measures of scale](https://www.itl.nist.gov/div898/handbook/eda/section3/eda356.htm).
The square-root term provides a count-noise floor when the measured deviation is zero. The lower recovery limit
prevents a lull just below the activation limit from ending a raid. Each elevated wave restarts the quiet timer.

These are detection heuristics, not calibrated probabilities. Overlapping samples are correlated. Normal reconnects
after startup can still resemble an attack. A sufficiently slow increase below the detection limits can enter the
baseline; volume alone cannot identify every attack. Events rejected by the proxy or before Paper fires these events
are not visible to this module.

## Ownership and bounds

Player join and command callbacks and asynchronous chat callbacks record counts directly. The module's existing
executor samples traffic and checks for quiet periods once per second, even when no events arrive.

The detector uses the module monitor to serialize counts, baseline updates, manual toggles, and automatic activation.
It reads trust from the quarantine owner's existing session state. It performs no database or filesystem I/O.
Quarantine activation updates module-owned state and sends messages; it does not mutate another player's entity.

Storage is fixed-size count buckets, 300 accepted samples, and at most 61 waiting samples per signal. NUSH does not
retain one object per event or player in the detector. Module unload cancels the timer before quarantine cleanup.
If the timer fails, NUSH logs the throwable and reports the detector as stopped.

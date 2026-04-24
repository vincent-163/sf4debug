# sf4debug — next-version ideas

0.5.0 shipped Groups 5, 6 and 7 on top of the 0.4.0 base. Remaining
rough edges and possible follow-ups:

## Known rough edges

- **/me fallback.** `/me` still returns an error for non-AE2 GUIs.
  Consider a generic container-items paginator as a fallback.
- **/eatUntilFull / /wait cancel.** `/cancel?tag=wait` etc. register
  flags via `WorkflowRoutes.registerCancelable(...)` but the long-
  running loops in `HelperRoutes` don't consult them yet. Wire them
  up on the next pass.
- **/screenshot formats.** Only PNG. JPEG/WebP would help bandwidth
  on remote tunnels.
- **/tabcomplete cursor.** The 1.12.2 packet ignores `cursor=`; the
  query echo is for forward-compat only.

## Possible additions

- **/macro.smelt / .craft / .equip.** High-level helpers on top of
  `ContainerRoutes`.
- **/block.mineInfo.** Harvestability + ticks-to-break with the held
  tool (read `state.getBlockHardness(world, pos)` + player efficiency).
- **/advancements.** Plumb Minecraft's `ClientAdvancementManager` to
  expose achievement progress.
- **/input.sequence.** A scripted sequence of `/input` + `/mouse`
  calls that step across multiple ticks.
- **Typed container writes.** `/anvil.rename+take`, `/enchant.pick?slot`,
  `/beacon.confirm?primary=&secondary=`, `/merchant.select?idx=`.

## Notes

- `/chat` is send (ActionRoutes); `/chatlog` is read (ObserveRoutes).
  The 0.4.0 path collision is fixed.
- `TickInput` owns all three aim plans (smooth, path, entity track)
  with full introspection via `/aimStatus`.

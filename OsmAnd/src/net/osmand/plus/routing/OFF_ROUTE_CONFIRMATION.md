# Off-route recalculation confirmation

Navigation settings expose `off_route_recalculation` per profile: 0 automatic,
1 ask first, 2 off. Walking defaults to ask first; other profiles retain automatic
behavior. Existing off-route and wrong-direction disable switches still apply.

The gate is applied only to location-driven recalculation during active guidance
with an existing route. Initial calculations, destination edits and explicit
settings changes retain their existing behavior. Both lateral deviation and
wrong-direction recalculation pass through the gate. Waiting, rejecting, Back,
or tapping outside the dialog keeps the route; rejection suppresses further
dialogs for that deviation episode. Returning to the route rearms confirmation.

RoutingHelper owns the gate under its monitor. Confirmation tokens are invalidated
by route replacement, navigation stop, profile/policy changes and returning to the
route. Approval permits the next location update to re-evaluate deviation before
requesting recalculation; it does not submit a stale saved location. Existing
recalculation throttling and calculation failure behavior remain in effect.

The Kotlin UI holds the foreground MapActivity weakly and owns its dialog on the
main thread. Leaving the activity dismisses the window but retains the pending
decision. Returning to the activity presents it again. No automatic approval,
timeout, background activity launch or permission request is used. TTS speaks the
localized confirmation once per episode, respecting voice mute and deviation
announcement settings; recorded voices use their existing off-route message.

Validation: `OffRouteRecalculationGateTest` covers policy transitions, repeated
fixes, rejection, approval, stale tokens and rearming. `OffRouteConfirmationTest`
exercises real RoutingHelper deviation detection with a synthetic route and the
dialog lifecycle without starting user navigation or modifying the track database.

Validation on 2026-09-28: all 1,162 Java tests completed with no failures and
14 skipped; the three gate tests passed. A standalone instrumentation runner
(`D:/Osm/build/offroute-tests/OffRouteRunner.java`) also passed on the connected
phone using real RoutingHelper updates and dialog buttons. Its log is
`D:/Osm/logs/offroute-device-test-20260928.log`. The AndroidX test source above
was not executed because its test dependencies were unavailable locally.
No outdoor walk, audible speech check, or full post-approval routing-engine
calculation was performed. Temporary test instrumentation was uninstalled.

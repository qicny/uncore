// See LICENSE for license details.

package uncore
import Chisel._
import cde.{Parameters, Field}


class BufferlessBroadcastHub(implicit p: Parameters) extends HierarchicalCoherenceAgent()(p) {

  // Create TSHRs for outstanding transactions
  val irelTrackerList =
    (0 until nReleaseTransactors).map(id =>
      Module(new BufferlessBroadcastVoluntaryReleaseTracker(id)))
  val iacqTrackerList = 
    (nReleaseTransactors until nTransactors).map(id =>
      Module(new BufferlessBroadcastAcquireTracker(id)))
  val trackerList = irelTrackerList ++ iacqTrackerList

  // Propagate incoherence flags
  trackerList.map(_.io.incoherent) foreach { _ := io.incoherent }

  // Create an arbiter for the one memory port
  val outerList = trackerList.map(_.io.outer)
  val outer_arb = Module(new ClientTileLinkIOArbiter(outerList.size)
                                                    (p.alterPartial({ case TLId => p(OuterTLId) })))
  outer_arb.io.in <> outerList
  io.outer <> outer_arb.io.out

  // Handle acquire transaction initiation
  val irel_vs_iacq_conflict =
    io.inner.acquire.valid &&
    io.inner.release.valid &&
    io.irel().conflicts(io.iacq())

  doInputRoutingWithAllocation(
    in = io.inner.acquire,
    outs = trackerList.map(_.io.inner.acquire),
    allocs = trackerList.map(_.io.alloc.iacq),
    allocOverride = !irel_vs_iacq_conflict)
  io.outer.acquire.bits.data := io.inner.acquire.bits.data
  io.outer.acquire.bits.addr_beat := io.inner.acquire.bits.addr_beat

  // Handle releases, which might be voluntary and might have data
  doInputRoutingWithAllocation(
    in = io.inner.release,
    outs = trackerList.map(_.io.inner.release),
    allocs = trackerList.map(_.io.alloc.irel))
  io.outer.release.bits.data := io.inner.release.bits.data
  io.outer.release.bits.addr_beat := io.inner.release.bits.addr_beat

  // Wire probe requests and grant reply to clients, finish acks from clients
  doOutputArbitration(io.inner.probe, trackerList.map(_.io.inner.probe))

  doOutputArbitration(io.inner.grant, trackerList.map(_.io.inner.grant))
  io.inner.grant.bits.data := io.outer.grant.bits.data
  io.inner.grant.bits.addr_beat := io.outer.grant.bits.addr_beat

  doInputRouting(io.inner.finish, trackerList.map(_.io.inner.finish))
}

class BufferlessBroadcastVoluntaryReleaseTracker(trackerId: Int)(implicit p: Parameters)
    extends BroadcastVoluntaryReleaseTracker(trackerId)(p) {

  // Tell the parent if any incoming messages conflict with the ongoing transaction
  routeInParent()
  io.alloc.iacq.can := Bool(false)

  // Start transaction by accepting inner release
  innerRelease(block_vol_ignt = pending_orel || vol_ognt_counter.pending)

  // A release beat can be accepted if we are idle, if its a mergeable transaction, or if its a tail beat
  // and if the outer relase path is clear 
  val irel_could_accept = state === s_idle || irel_can_merge || irel_same_xact
  io.inner.release.ready := irel_could_accept &&
    (!io.irel().hasData() || io.outer.release.ready)

  // Dispatch outer release
  outerRelease(coh = outer_coh.onHit(M_XWR))
  io.outer.grant.ready := state === s_busy && io.inner.grant.ready // bypass data

  quiesce() {}
}

class BufferlessBroadcastAcquireTracker(trackerId: Int)(implicit p: Parameters)
    extends BroadcastAcquireTracker(trackerId)(p) {

  // Setup IOs used for routing in the parent
  routeInParent()
  io.alloc.irel.can := Bool(false)

  // First, take care of accpeting new acquires or secondary misses
  // Handling of primary and secondary misses' data and write mask merging
  innerAcquire(
    can_alloc = Bool(false),
    next = s_inner_probe)

  val iacq_could_accept = state === s_outer_acquire || iacq_can_merge || iacq_same_xact
  io.inner.acquire.ready := iacq_could_accept && 
    (!io.iacq().hasData() || io.outer.acquire.fire())

  // Track which clients yet need to be probed and make Probe message
  innerProbe(
    inner_coh.makeProbe(curr_probe_dst, xact_iacq, xact_addr_block),
    s_outer_acquire)

  // Handle incoming releases from clients, which may reduce sharer counts
  // and/or write back dirty data, and may be unexpected voluntary releases
  def irel_can_merge = io.irel().conflicts(xact_addr_block) &&
                         io.irel().isVoluntary() &&
                         !vol_ignt_counter.pending &&
                         (state =/= s_idle)

  innerRelease(block_vol_ignt = vol_ognt_counter.pending) 

  val irel_could_accept = irel_can_merge || irel_same_xact
  io.inner.release.ready := irel_could_accept &&
    (!io.irel().hasData() || io.outer.release.ready)

  // If there was a writeback, forward it outwards
  outerRelease(
    coh = outer_coh.onHit(M_XWR),
    buffering = Bool(false))

  // Send outer request for miss
  outerAcquire(
    caching = !xact_iacq.isBuiltInType(),
    buffering = Bool(false),
    coh = outer_coh,
    next = s_busy)

  // Handle the response from outer memory
  io.outer.grant.ready := state === s_busy && io.inner.grant.ready // bypass data

  // Acknowledge or respond with data
  innerGrant(external_pending = pending_orel || ognt_counter.pending || vol_ognt_counter.pending)

  when(iacq_is_allocating) { initializeProbes() }

  // Wait for everything to quiesce
  quiesce() {}
}

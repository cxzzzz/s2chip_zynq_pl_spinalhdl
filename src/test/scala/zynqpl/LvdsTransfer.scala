package zynqpl

import org.scalatest.Tag
import org.scalatest.funsuite.AnyFunSuite
import spinal.core
import spinal.core._
import spinal.lib._
import spinal.lib.bus.simple.{PipelinedMemoryBus, PipelinedMemoryBusConfig}

class LvdsTransferTestTop( lvdsconfig:LvdsTransferConfig
                           ) extends Component {

  val busconfig = lvdsconfig.bus

  val io = new Bundle {
    val cmd = new Bundle {
      val write = slave(Stream(LvdsTransferCmd(lvdsconfig)))
      val read = slave(Stream(LvdsTransferCmd(lvdsconfig)))
    }
  }

  val lvdsOut = new LvdsOutTransfer(lvdsconfig)
  val lvdsIn = new LvdsInTransfer(lvdsconfig)

  lvdsIn.io.cmd <> io.cmd.read
  lvdsOut.io.cmd <> io.cmd.write

  lvdsOut.io.data.lvds <> lvdsIn.io.data.lvds

  lvdsOut.io.clk_diff.p := clockDomain.readClockWire.asBits
  lvdsOut.io.clk_diff.n := ~clockDomain.readClockWire.asBits

  lvdsIn.io.reset := clockDomain.readResetWire

  val stdQueue = StreamFifoCC(Bits(busconfig.dataWidth bits), 1024, clockDomain, clockDomain)

  stdQueue.io.pop.ready := False
  lvdsIn.io.data.out.cmd.ready := stdQueue.io.pop.valid
  lvdsIn.io.data.out.rsp.data.assignDontCare()
  lvdsIn.io.data.out.rsp.valid := False
  when(lvdsIn.io.data.out.cmd.fire) {
    stdQueue.io.pop.ready := True
    core.assert(!lvdsIn.io.data.out.cmd.write, "lvdsin pipelinebus error")
    core.assert(stdQueue.io.pop.fire, "lvdsin stddata error")
    core.assert(stdQueue.io.pop.payload === lvdsIn.io.data.out.cmd.data)
  }

  stdQueue.io.push.valid := True
  val address = Reg(UInt(busconfig.addressWidth bits))

  lvdsOut.io.data.in.rsp.data := ( RegNext( address.asBits ) init 0 )
  lvdsOut.io.data.in.rsp.valid := RegNext( lvdsOut.io.data.in.cmd.fire ) init False
  lvdsOut.io.data.in.cmd.ready := stdQueue.io.push.ready

  stdQueue.io.push.payload := address.asBits
  when(lvdsOut.io.data.in.cmd.fire){
    stdQueue.io.push.valid := True
    core.assert( stdQueue.io.push.fire )
    address := address + 1
  }
}


class LvdsTransferTest extends AnyFunSuite{


  test( "LvdsTransfer 读写测试" ){

    val lvdsConfig = LvdsConfig( 2 , 8 )
    val busConfig = PipelinedMemoryBusConfig( addressWidth = 32 , dataWidth = 32 )
    SpinalVerilog(
      new LvdsTransferTestTop(
        LvdsTransferConfig( busConfig , lvdsConfig )
      )
    )
  }

}

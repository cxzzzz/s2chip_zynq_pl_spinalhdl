package zynqpl

import spinal.core._
import spinal.lib._
import spinal.lib.bus.amba4.axi.{Axi4, Axi4B, Axi4Bus, Axi4Config, Axi4CrossbarFactory, Axi4SharedArbiter, Axi4SharedDecoder, Axi4SharedOnChipRam, Axi4SharedToApb3Bridge, Axi4SharedToBram}
import spinal.lib.bus.bram.BRAM
import spinal.lib.bus.misc.SizeMapping
import spinal.lib.bus.regif.AccessType.RW
import spinal.lib.bus.regif.{Apb3BusInterface, BusInterface}
import spinal.lib.bus.simple.{PipelinedMemoryBus, PipelinedMemoryBusConfig}

case class PLTransferConfig( out : LvdsTransferConfig , in :LvdsTransferConfig , axi:Axi4Config ){

  assert( out.lvds == in.lvds )
  assert( out.bus == in.bus )

  val outMemSize = ( 1 << out.bus.addressWidth ) * ( out.bus.dataWidth / 8)
  val inMemSize = ( 1 << in.bus.addressWidth ) * ( in.bus.dataWidth / 8)
}

class PLTransferTop( config:PLTransferConfig ) extends Component{

  val io = new Bundle{

    val lvds = new Bundle{
      val in = slave( LvdsBus(config.in.lvds) )
      val out = master( LvdsBus(config.out.lvds) )
    }

    val axi = slave( new Axi4( config.axi ))

    val clk = new Bundle{
      //val axi = out(Bool())
      val lvdsDS = in( DiffIO(1) )
    }
  }

  //val clk = new LvdsClk()

  //val divClkCD = ClockDomain.internal("div")
  //divClkCD.clock := clk.io.clk.div
  //divClkCD.reset := clk.io.reset

  //io.clk.axi := clk.io.clk.div

  //val div = new ClockingArea( divClkCD ) {

    val lvdsOut = new LvdsOutTransfer( config.out )
    val lvdsIn = new LvdsInTransfer( config.in )

    lvdsOut.io.clk_diff := io.clk.lvdsDS

    val lvdsReset = RegInit( True )
    when( lvdsReset ){
      lvdsReset := False
    }

    lvdsIn.io.reset := lvdsReset

    val outMemBus, inMemBus = new Axi4SharedToBram(addressBRAMWidth = config.out.bus.addressWidth, addressAxiWidth = config.axi.addressWidth,
      dataWidth = config.axi.dataWidth, idWidth = 0)

    val axi4slaves =
      Axi4SharedDecoder( config.axi ,
        sharedDecodings = Seq(
          new SizeMapping( base = 0x1000 , size = config.outMemSize )  ,
          new SizeMapping( base = 0x1000 + config.outMemSize , size = config.inMemSize ) ,
          new SizeMapping( base = 0 , size = 0x1000 )
        ),
        readDecodings = Seq() , writeDecodings = Seq()
    )

    val apb3slaves = Axi4SharedToApb3Bridge( addressWidth = config.axi.addressWidth , dataWidth = config.axi.dataWidth , idWidth = config.axi.idWidth )

    axi4slaves.io.sharedOutputs(2) >> apb3slaves.io.axi
    val apb3Bridge = BusInterface(  apb3slaves.io.apb , sizeMap = SizeMapping( base = 0 , size = 0x1000) , selID = 0 , regPre = "" )

    val outCmdRegs = Array.fill(4){ apb3Bridge.newReg( doc = "outCmd") }
    val outCmd = LvdsTransferCmd( config.out )
      outCmd.connectRegInst( outCmdRegs )
    val outCmdHit = outCmdRegs.last.hitDoWrite

    val inCmdRegs  = Array.fill(4){ apb3Bridge.newReg( doc = "inCmd") }
    val inCmd  = LvdsTransferCmd( config.in )
      inCmd.connectRegInst( inCmdRegs )
    val inCmdHit = inCmdRegs.last.hitDoWrite

    val outCmdQueue = StreamFifo(  outCmd , 4 )
    val inCmdQueue = StreamFifo( inCmd , 4 )

    outCmdQueue.io.push.valid := outCmdHit
    outCmdQueue.io.push.payload := outCmd
    when( outCmdQueue.io.push.valid ){
      assert( outCmdQueue.io.push.fire )
    }

    inCmdQueue.io.push.valid := inCmdHit
    inCmdQueue.io.push.payload := inCmd
    when( inCmdQueue.io.push.valid ){
      assert( inCmdQueue.io.push.fire )
    }

    val outMem, inMem = Mem(Bits(config.out.bus.dataWidth bits), (1 << config.out.bus.addressWidth) * 8 / config.out.bus.dataWidth )

    def bramBus2Mem[T <: Data](bram: BRAM, mem: Mem[T] , write:Boolean ):Area = new Area{
      if( write ){
        bram.rddata := mem.readWriteSync(address = bram.addr, data = bram.wrdata.toDataType(mem.wordType()), enable = bram.en, write = bram.we.orR, mask = bram.we ).asBits
      }else{
        bram.rddata := mem.readSync(address = bram.addr ).asBits
        when( bram.en ){
          assert(!bram.we.orR)
        }
      }
    }

    def pipelinedBus2Mem[T <: Data](pipe: PipelinedMemoryBus, mem: Mem[T] , write:Boolean): Unit = {
      pipe.rsp.valid := RegNext(pipe.cmd.fire && !pipe.cmd.write) init False
      if( write ) {
        pipe.rsp.data := mem.readWriteSync(address = pipe.cmd.address, data = pipe.cmd.data.toDataType(mem.wordType()), enable = pipe.cmd.fire, write = pipe.cmd.write, mask = pipe.cmd.mask).asBits
      }else{
        pipe.rsp.data := mem.readSync(address = pipe.cmd.address ).asBits
        when( pipe.cmd.fire){
          assert(!pipe.cmd.write)
        }
      }
      pipe.cmd.ready := True
    }

    bramBus2Mem( outMemBus.io.bram , outMem , write = true )
    bramBus2Mem( inMemBus.io.bram , inMem , write = false )

    pipelinedBus2Mem( lvdsOut.io.data.in  , outMem , write = false )
    pipelinedBus2Mem( lvdsIn.io.data.out  , inMem  , write = true )

    lvdsOut.io.data.lvds <> io.lvds.out
    lvdsIn.io.data.lvds <> io.lvds.in

    lvdsOut.io.cmd << outCmdQueue.io.pop
    lvdsIn.io.cmd << inCmdQueue.io.pop

    io.axi.toShared() >>  axi4slaves.io.input
    axi4slaves.io.sharedOutputs(0)  >> outMemBus.io.axi
    axi4slaves.io.sharedOutputs(1)  >> inMemBus.io.axi

  //}

}

object PLTransferTop extends App{
  def main(): Unit ={

      val lvdsTransferConfig = LvdsTransferConfig( PipelinedMemoryBusConfig( addressWidth = 32 , dataWidth = 64 ) , lvds = LvdsConfig( 3 , serialFactor = 8 ) )
      val axiConfig = Axi4Config( addressWidth = 32 , dataWidth = 64 , idWidth = 0 )
      val config = PLTransferConfig( out = lvdsTransferConfig , in = lvdsTransferConfig , axi = axiConfig )

      SpinalVerilog( config = SpinalConfig() )( new PLTransferTop( config)  )
  }

  main()
}

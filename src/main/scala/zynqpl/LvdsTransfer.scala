package zynqpl

import spinal.core._
import spinal.lib._
import spinal.lib.bus.regif.AccessType.RW
import spinal.lib.bus.regif.RegInst
import spinal.lib.bus.simple.{PipelinedMemoryBus, PipelinedMemoryBusCmd, PipelinedMemoryBusConfig}
import spinal.lib.fsm.{EntryPoint, State, StateMachine}

case class LvdsTransferCmd( config: LvdsTransferConfig ) extends Bundle{
	val data = new Bundle{
		val startaddr = UInt( config.bus.addressWidth bits )
    val length = UInt( config.bus.addressWidth bits )
	}
  val sync = new Bundle{
		val cycle = UInt( 32 bits)
	}

	def connectRegInst( ris:Array[RegInst] )  = new Area{
		data.startaddr := ris(0).field( data.startaddr.getWidth bits , RW , doc = "data_startaddr" ).asUInt
		data.length := ris(1).field( data.length.getWidth bits, RW ,doc = "data_length" ).asUInt
		sync.cycle := ris(2).field( sync.cycle.getWidth bits, RW ,doc = "siync_cycle" ).asUInt
	}

}

case class LvdsTransferConfig(bus: PipelinedMemoryBusConfig , lvds:LvdsConfig  ){
	assert( bus.dataWidth % 8 == 0 )
}

object LvdsTransfer{

	val magicNumber = new {
		def Trans = B"8'b0000_0001"
		def Sync = B"8'b1001_0011"
		def Idle = B"8'b0000_0000"
	}

}

class LvdsOutTransfer( config:LvdsTransferConfig ) extends Component{

	assert( config.lvds.systemWidth > 1 )
	assert( config.lvds.serialFactor == 8 )

	val io= new Bundle{
		val cmd = slave Stream( LvdsTransferCmd( config:LvdsTransferConfig ) )
    val data = new Bundle{
			val in = master( PipelinedMemoryBus( config.bus ) )
			val lvds = master( LvdsBus( config.lvds) )
		}
		val clk_diff = in ( DiffIO( 1 ) )
	}

	val lvds = new LvdsOut( config.lvds )

	val counter = new Area{
		val sync = Counter( io.cmd.sync.cycle.getWidth bits)
		val address = Reg( UInt( config.bus.addressWidth bits ) ) init (0)
	}

	//val data = Vec.fill( config.lvds.systemWidth ){ Bits( config.lvds.serialFactor bits) }
	val ctrlBits = Bits( config.lvds.serialFactor bits)
  val dataBits = Bits( config.lvds.serialFactor * (config.lvds.systemWidth - 1)  bits)

	ctrlBits := LvdsTransfer.magicNumber.Idle
	dataBits.assignDontCare()

	lvds.io.data.in := ( dataBits ## ctrlBits )
	lvds.io.clk_diff := io.clk_diff

	io.data.lvds <> lvds.io.data.out


	io.data.in.cmd.payload.address := counter.address
	io.data.in.cmd.payload.data.assignDontCare()
	io.data.in.cmd.payload.mask.setAll()
	io.data.in.cmd.payload.write := False
	io.data.in.cmd.valid := False

	io.cmd.ready := False

	val ctrl = new Area{
		val fsm = new StateMachine{
			val IDLE:State = new State with EntryPoint{
				whenIsActive{
          counter.sync.clear()
          counter.address := io.cmd.data.startaddr
  				when( io.cmd.valid ){
						goto( SYNC )
					}
				}
			}

			val SYNC:State = new State{
				whenIsActive{
          ctrlBits := LvdsTransfer.magicNumber.Sync
          counter.sync.increment()
					when( counter.sync.value >= io.cmd.sync.cycle ){
						goto( TRANS_WAIT )
					}
				}
			}

			val TRANS_WAIT:State = new State{
				whenIsActive{
					ctrlBits := LvdsTransfer.magicNumber.Sync

         	counter.address := counter.address + 1
          io.data.in.cmd.valid := True
					assert( io.data.in.cmd.ready === True )

					goto(TRANS)
				}
			}

			val addressR = RegNext( counter.address ) init(0)
      val dataVec = Vec.fill( config.bus.dataWidth / 8 ){ Bits(8 bits) }
			dataVec.assignFromBits( io.data.in.rsp.payload.data )

			val TRANS:State = new State{
				whenIsActive{
					dataBits := ( dataVec.asBits >> ( ( addressR % (config.bus.dataWidth / 8 ) ) * 8 )  ).resized
						//dataVec( ( addressR % (config.bus.dataWidth / 8 ) ).resized   )
					ctrlBits := LvdsTransfer.magicNumber.Trans

					counter.address := counter.address + 1
					io.data.in.cmd.valid := True
					assert( io.data.in.cmd.ready === True )
					assert( io.data.in.rsp.valid === True )

          when( counter.address === io.cmd.data.startaddr + io.cmd.data.length ){
						io.cmd.ready := True
						goto(IDLE)
					}
				}
			}
		}
	}
}

class LvdsInTransfer( config:LvdsTransferConfig ) extends Component{

	assert( config.lvds.systemWidth > 1 )
	assert( config.lvds.serialFactor == 8 )
	assert(  config.bus.dataWidth % (config.lvds.deviceWidth - config.lvds.serialFactor)  == 0  , s"${config.bus.dataWidth} , ${config.lvds.deviceWidth}")

	val io= new Bundle{
		val ctrl = new Bundle{
			val error = out Bool()
		}
		val cmd = slave Stream( LvdsTransferCmd( config:LvdsTransferConfig ) )
		val data = new Bundle{
			val out = master( PipelinedMemoryBus( config.bus ) )
			val lvds = slave( LvdsBus( config.lvds) )
		}
		val reset = in Bool()
	}



	//val busCC = Stream( PipelinedMemoryBusCmd( config.bus ) )

	val lvds = new LvdsIn( config.lvds )
	/*
	lvdsDivCD.clock := lvds.io.clk_div
	lvdsDivCD.reset := lvds.io.reset
	 */

	val lvdsDivCD = new ClockDomain( lvds.io.clk_div , lvds.io.reset  )
	//ClockDomain.internal("div" )

	lvds.io.bitslip := False
	lvds.io.data.in <> io.data.lvds
	lvds.io.reset := io.reset


	val busCCFifo = StreamFifoCC( PipelinedMemoryBusCmd( config.bus ) , depth = 32 , pushClock = lvdsDivCD , popClock = clockDomain )
	//val cmdCCFifo = StreamFifoCC( LvdsTransferCmd( config) , depth = 4 , pushClock = ClockDomain.current ,  popClock = lvdsDivCD )
	val cmdCCFifo = StreamCCByToggle( LvdsTransferCmd( config) , inputClock = clockDomain , outputClock = lvdsDivCD )

	io.data.out.cmd << busCCFifo.io.pop
	cmdCCFifo.io.input << io.cmd



	val div = new ClockingArea(  lvdsDivCD ) {


		val errorR = RegInit(False).addTag(crossClockDomain)

		io.ctrl.error := errorR

		val counter = new Area {
			val address = Reg(UInt(config.bus.addressWidth bits)) init (0)
		}

		//val data = Vec.fill( config.lvds.systemWidth ){ Bits( config.lvds.serialFactor bits) }

		//lvds.io.data.in := ( dataBits ## ctrlBits )

		//io.data.out.cmd.payload.address := counter.data
		busCCFifo.io.push.address := counter.address
		val shift = new Area {
			val maskwidth = (config.bus.dataWidth / 8)
			val maskshift = (counter.address % maskwidth).resize(config.bus.dataWidth)
			val datashift = (maskshift * 8).resize(config.bus.dataWidth)
		}
		busCCFifo.io.push.data := (lvds.io.data.out << shift.datashift).resized
		busCCFifo.io.push.mask := {
			val maskwidth = (config.bus.dataWidth / 8)
			val mask0 = (1 << maskwidth) - 1
			B(mask0) << shift.maskshift
		}

		busCCFifo.io.push.write := True
		busCCFifo.io.push.valid := False


		val ctrlBits = Bits(config.lvds.serialFactor bits)
		val dataBits = Bits(config.lvds.serialFactor * (config.lvds.systemWidth - 1) bits)

		//lvds.io.data.in := ( dataBits ## ctrlBits )
		ctrlBits := lvds.io.data.out((ctrlBits.getWidth - 1) downto 0)
		dataBits := lvds.io.data.out((lvds.io.data.out.getWidth - 1) downto (ctrlBits.getWidth))


		cmdCCFifo.io.output.ready := False

		val ctrl = new Area {
			val fsm = new StateMachine {
				val IDLE: State = new State with EntryPoint {
					whenIsActive {
						counter.address:= cmdCCFifo.io.output.data.startaddr
						when(cmdCCFifo.io.output.valid) {
							errorR := False
							goto(SYNC_CHECK)
						}
					}
				}

				val SYNC_CHECK: State = new State {
					whenIsActive {
						when(ctrlBits === LvdsTransfer.magicNumber.Sync) {
							goto(TRANS)
						}.otherwise {
							goto(SYNC_SET_0)
						}
					}
				}

				val SYNC_SET_0: State = new State {
					whenIsActive {
						lvds.io.bitslip := True
						goto(SYNC_SET_1)
					}
				}

				val SYNC_SET_1: State = new State {
					whenIsActive {
						lvds.io.bitslip := False
						goto(SYNC_CHECK)
					}
				}


				val TRANS: State = new State {
					whenIsActive {
						counter.address := counter.address + (config.bus.dataWidth / 8)

						busCCFifo.io.push.valid := True
						assert(busCCFifo.io.push.ready)
						assert(ctrlBits === LvdsTransfer.magicNumber.Trans)
						when(ctrlBits =/= LvdsTransfer.magicNumber.Trans) {
							errorR := True
						}
						when(counter.address + 1 === cmdCCFifo.io.output.data.startaddr + cmdCCFifo.io.output.data.length) {
							cmdCCFifo.io.output.ready := True
							goto(IDLE)
						}
					}
				}
			}
		}
	}
}

object LvdsOutTransfer extends App{
	def main(): Unit ={
			val config = LvdsTransferConfig(
				PipelinedMemoryBusConfig( 16 , 32) , LvdsConfig( 3 , 8 )
			)
			SpinalVerilog( new LvdsOutTransfer( config ) )
	}
	main()
}
object LvdsInTransfer extends App{
	def main(): Unit ={
		val config = LvdsTransferConfig(
			PipelinedMemoryBusConfig( 16 , 32) , LvdsConfig( 3 , 8 )
		)
		SpinalVerilog( new LvdsInTransfer( config )  ).printPruned()

	}
	main()
}

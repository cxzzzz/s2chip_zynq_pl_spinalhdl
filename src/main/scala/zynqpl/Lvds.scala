package zynqpl

import spinal.core._
import spinal.lib._

case class LvdsConfig(  systemWidth:Int , serialFactor:Int ){
  val deviceWidth = systemWidth *serialFactor
}

case class DiffIO( width:Int ) extends Bundle{
  val p , n = Bits( width bits)
}

case class LvdsBus( config:LvdsConfig ) extends Bundle with IMasterSlave{
  val clk = DiffIO( 1 )
  val data = DiffIO( config.systemWidth)
  def asMaster():Unit = {
    out( clk , data )
  }
}

class LvdsInBB(config:LvdsConfig ) extends  BlackBox{
  import config._
  assert( deviceWidth % systemWidth == 0 )
  addGeneric( "SYS_W" , systemWidth )
  addGeneric( "DEV_W" , deviceWidth )

  val io = new Bundle{
    val clk_in_p , clk_in_n = in Bool()
    val clk_div_out = out Bool()
    val data_in_from_pins_p , data_in_from_pins_n = in Bits( config.systemWidth bits)
    val data_in_to_device = out Bits( config.deviceWidth bits )
    val bitslip = in Bits( config.systemWidth bits)
    val clk_reset , io_reset = in Bool()
  }

  noIoPrefix()
}

class LvdsIn( config:LvdsConfig , blackbox: Boolean = true ) extends Component {
  import config._

  val io = new Bundle{
    //val clk_div = out Bool()
    val data = new Bundle{
      val in = slave( LvdsBus(config) )
      val out = spinal.core.out( Bits( deviceWidth bits ) )
    }

    val reset = in Bool()

    val clk_div = out Bool()

    val bitslip = in Bool()
  }

  val bb = new LvdsInBB( config )

  /*
  def divClockDomain = {

    val cd = ClockDomain.internal(
      name = "div"
    )
    cd.clock := bb.io.clk_div_out
    cd.reset := io.reset
    cd
  }
   */

  io.clk_div := bb.io.clk_div_out

  bb.io.clk_in_p := io.data.in.clk.p.asBool
  bb.io.clk_in_n := io.data.in.clk.n.asBool
  bb.io.data_in_from_pins_p <> io.data.in.data.p
  bb.io.data_in_from_pins_n <> io.data.in.data.n
  bb.io.data_in_to_device <> io.data.out
  bb.io.bitslip := (Vec.fill( config.systemWidth ){ io.bitslip}).asBits

  bb.io.clk_reset := io.reset
  bb.io.io_reset  := io.reset

}

class LvdsOutBB( config:LvdsConfig ) extends BlackBox{

  import config._
  assert( deviceWidth % systemWidth == 0 )

  addGeneric( "SYS_W" , systemWidth )
  addGeneric( "DEV_W" , deviceWidth )

  val io = new Bundle{
    val clk_in = in Bool()
    val clk_div_in = in Bool()
    val data_out_to_pins_p , data_out_to_pins_n = out Bits( config.systemWidth bits)
    val data_out_from_device = in Bits( config.deviceWidth bits )
    val io_reset = in Bool()
  }

  mapCurrentClockDomain( io.clk_div_in , reset = io.io_reset )

  noIoPrefix()

}

class LvdsOut( config:LvdsConfig , blackbox:Boolean = true) extends BlackBox{

  import config._
	assert( deviceWidth % systemWidth == 0 )

  val io = new Bundle{
    val clk_diff = in( DiffIO(1) )
    val data = new Bundle{
      val in = spinal.core.in( Bits( deviceWidth bits ) )
      val out = master( LvdsBus(config) )
    }
  }


  val impl = if( blackbox ){new Area{
      val bb:LvdsOutBB =  new LvdsOutBB( config )
      bb.io.clk_in := io.clk_diff.p.asBool
      //mapCurrentClockDomain( bb.io.clk_div_in , reset = bb.io.io_reset )
      bb.io.data_out_to_pins_p <> io.data.out.data.p
      bb.io.data_out_to_pins_n <> io.data.out.data.n
      bb.io.data_out_from_device <> io.data.in

      io.data.out.clk <> io.clk_diff

    }
  }else {new Area{

      val fifo = new StreamFifoCC( io.data.in , Bits(1) ) 

      io.data.out.clk := clk_diff

      io.data.out.data.p = 
      io.data.out.data.n = 

    }
  }

}
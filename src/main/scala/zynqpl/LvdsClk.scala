package zynqpl

import spinal.core._
import spinal.lib._


class OBUFGDS extends BlackBox{

  val io = new Bundle{
    val O,OB = out Bool()
    val I = in Bool()
  }

  noIoPrefix()
}

class LvdsClkBB extends BlackBox {

  setBlackBoxName("clk_lvds")
  noIoPrefix()

  val io = new Bundle{
    val clk_div = out Bool()
    val clk_lvds = out Bool()

    val reset = in Bool()
    val locked = out Bool()
    val clk_in1 = in Bool()
  }

  mapCurrentClockDomain( io.clk_in1 , io.reset )

}

class LvdsClk extends Component{
  val io = new Bundle{
    val clk= new Area{
      val div , lvds = out Bool()
      val lvdsDS = out ( DiffIO(1) )
    }

    val reset = out ( Bool() )
  }

  val lvdsclkbb = new LvdsClkBB()

  val lvdsClkObufDS = new OBUFGDS()

  lvdsClkObufDS.io.I := io.clk.lvds
  io.clk.lvdsDS.p := lvdsClkObufDS.io.O.asBits
  io.clk.lvdsDS.n := lvdsClkObufDS.io.OB.asBits

  io.clk.div := lvdsclkbb.io.clk_div
  io.clk.lvds := lvdsclkbb.io.clk_lvds

  io.reset :=  RegNext( !lvdsclkbb.io.locked ) init True
}

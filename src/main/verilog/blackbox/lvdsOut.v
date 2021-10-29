
module lvdsOut
   // width of the data for the system
 #(parameter SYS_W = 1,
   // width of the data for the device
   parameter DEV_W = 1)
 (
  // From the device out to the system
  input  [DEV_W-1:0] data_out_from_device,
  output [SYS_W-1:0] data_out_to_pins_p,
  output [SYS_W-1:0] data_out_to_pins_n,
  input              clk_in_p,      // Differential clock from IOB
  input              clk_in_n,
  output             clk_out,
  input              io_reset);


  lvdsOut_selectio_wiz
  #(
   .SYS_W(SYS_W),
   .DEV_W(DEV_W)
  )
  inst
 (
   .data_out_from_device(data_out_from_device),
   .data_out_to_pins_p(data_out_to_pins_p),
   .data_out_to_pins_n(data_out_to_pins_n),
   .clk_in_p(clk_in_p),
   .clk_in_n(clk_in_n),
   .clk_out(clk_out),
   .io_reset(io_reset)
);

endmodule
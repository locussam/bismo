// Copyright (c) 2018 Norwegian University of Science and Technology (NTNU)
//
// BSD v3 License
//
// All rights reserved.
//
// Redistribution and use in source and binary forms, with or without
// modification, are permitted provided that the following conditions are met:
//
// * Redistributions of source code must retain the above copyright notice, this
//   list of conditions and the following disclaimer.
//
// * Redistributions in binary form must reproduce the above copyright notice,
//   this list of conditions and the following disclaimer in the documentation
//   and/or other materials provided with the distribution.
//
// * Neither the name of [project] nor the names of its
//   contributors may be used to endorse or promote products derived from
//   this software without specific prior written permission.
//
// THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
// AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
// IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
// DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE
// FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
// DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
// SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
// CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
// OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
// OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.

package bismo

import Chisel._
import fpgatidbits.ocm._
import fpgatidbits.streams._
import fpgatidbits.PlatformWrapper._

// ExecStage is one of thre three components of the BISMO pipeline, which
// contains the actual DotProductArray, BRAMs for input matrix storage, and
// SequenceGenerators to pull data from BRAMs into the compute array

class ExecStageParams(
  // parameters for the DotProductArray
  val dpaParams: DotProductArrayParams,
  // number of L0 tiles that can be stored on-chip for LHS and RHS matrices
  val lhsTileMem: Int,
  val rhsTileMem: Int,
  // how much to increment the tile mem address to go to next tile (due to
  // asymmetric BRAM between fetch/execute)
  val tileMemAddrUnit: Int,
  // levels of registers before (on address input) and after (on data output)
  // of each tile memory BRAM
  val bramInRegs: Int = 1,
  val bramOutRegs: Int = 1,
  // number of entries in the result mem
  val resEntriesPerMem: Int = 2
) extends PrintableParam {
  def headersAsList(): List[String] = {
    return dpaParams.headersAsList() ++ List("lhsTileMem", "rhsTileMem")
  }

  def contentAsList(): List[String] = {
    return dpaParams.contentAsList() ++ List(lhsTileMem, rhsTileMem).map(_.toString)
  }

  // latency of instantiated DPA
  val dpaLatency: Int = dpaParams.getLatency()
  // contributed latency of DPA due to BRAM pipelining
  // addr/data pipeline regs plus 1 because BRAM is inherently sequential
  val myLatency_read: Int = bramInRegs + bramOutRegs + 1
  // write latency to result memory
  val myLatency_write: Int = 0
  def getLatency(): Int = {
    return myLatency_read + myLatency_write + dpaLatency
  }
  def getBRAMReadLatency(): Int = {
    return myLatency_read
  }
  def getBRAMWriteLatency(): Int = {
    return myLatency_write
  }

  // convenience functions to access child parameters
  // LHS rows
  def getM(): Int = {
    return dpaParams.m
  }
  // popcount width
  def getK(): Int = {
    return dpaParams.dpuParams.pcParams.numInputBits
  }
  // RHS rows
  def getN(): Int = {
    return dpaParams.n
  }
  // number of bits per result word
  def getResBitWidth(): Int = {
    return dpaParams.dpuParams.accWidth
  }
}

// interface to hardware config available to software
class ExecStageCfgIO() extends Bundle {
  val config_dpu_x = UInt(OUTPUT, width = 32)
  val config_dpu_y = UInt(OUTPUT, width = 32)
  val config_dpu_z = UInt(OUTPUT, width = 32)
  val config_lhs_mem = UInt(OUTPUT, width = 32)
  val config_rhs_mem = UInt(OUTPUT, width = 32)

  override def cloneType: this.type =
    new ExecStageCfgIO().asInstanceOf[this.type]
}

// interface towards controller for the execute stage
class ExecStageCtrlIO(myP: ExecStageParams) extends PrintableBundle {
  val lhsOffset = UInt(width = 32)   // start offset for LHS tiles
  val rhsOffset = UInt(width = 32)   // start offset for RHS tiles
  val numTiles = UInt(width = 32)    // num of L0 tiles to execute
  // how much left shift to use
  val shiftAmount = UInt(width = log2Up(myP.dpaParams.dpuParams.maxShiftSteps+1))
  // negate during accumulation
  val negate = Bool()
  // clear accumulators prior to first accumulation
  val clear_before_first_accumulation = Bool()
  // write to result memory at the end of current execution
  val writeEn = Bool()
  // result memory address to use for writing
  val writeAddr = UInt(width = log2Up(myP.resEntriesPerMem))

  override def cloneType: this.type =
    new ExecStageCtrlIO(myP).asInstanceOf[this.type]

  val printfStr = "(offs lhs/rhs = %d/%d, ntiles = %d, << %d, w? %d/%d)\n"
  val printfElems = {() =>  Seq(
    lhsOffset, rhsOffset, numTiles, shiftAmount, writeEn, writeAddr
  )}
}

// interface towards tile memories (LHS/RHS BRAMs)
class ExecStageTileMemIO(myP: ExecStageParams) extends Bundle {
  val lhs_req = Vec.fill(myP.getM()) {
    new OCMRequest(myP.getK(), log2Up(myP.lhsTileMem * myP.tileMemAddrUnit)).asOutput
  }
  val lhs_rsp = Vec.fill(myP.getM()) {
    new OCMResponse(myP.getK()).asInput
  }
  val rhs_req = Vec.fill(myP.getN()) {
    new OCMRequest(myP.getK(), log2Up(myP.rhsTileMem * myP.tileMemAddrUnit)).asOutput
  }
  val rhs_rsp = Vec.fill(myP.getN()) {
    new OCMResponse(myP.getK()).asInput
  }

  override def cloneType: this.type =
    new ExecStageTileMemIO(myP).asInstanceOf[this.type]
}

// interface towards result stage
class ExecStageResMemIO(myP: ExecStageParams) extends Bundle {
  val req = Vec.fill(myP.getM()) { Vec.fill(myP.getN()) {
    new OCMRequest(
      myP.getResBitWidth(), log2Up(myP.resEntriesPerMem)
    ).asOutput
  }}

  override def cloneType: this.type =
    new ExecStageResMemIO(myP).asInstanceOf[this.type]
}

class ExecStage(val myP: ExecStageParams) extends Module {
  val io = new Bundle {
    // base control signals
    val start = Bool(INPUT)                   // hold high while running
    val done = Bool(OUTPUT)                   // high when done until start=0
    val cfg = new ExecStageCfgIO()
    val csr = new ExecStageCtrlIO(myP).asInput
    val tilemem = new ExecStageTileMemIO(myP)
    val res = new ExecStageResMemIO(myP)
  }
  // expose generated hardware config to software
  io.cfg.config_dpu_x := UInt(myP.getM())
  io.cfg.config_dpu_y := UInt(myP.getN())
  io.cfg.config_dpu_z := UInt(myP.getK())
  io.cfg.config_lhs_mem := UInt(myP.lhsTileMem)
  io.cfg.config_rhs_mem := UInt(myP.rhsTileMem)
  // the actual compute array
  val dpa = Module(new DotProductArray(myP.dpaParams)).io
  // instantiate sequence generator for BRAM addressing
  // the tile mem is addressed in terms of the narrowest access
  val tileAddrBits = log2Up(myP.tileMemAddrUnit * math.max(myP.lhsTileMem, myP.rhsTileMem))
  val seqgen = Module(new SequenceGenerator(tileAddrBits)).io
  seqgen.init := UInt(0)
  seqgen.count := io.csr.numTiles
  seqgen.step := UInt(myP.tileMemAddrUnit)
  seqgen.start := io.start
  // account for latency inside datapath to delay finished signal
  io.done := ShiftRegister(seqgen.finished, myP.getLatency())

  // wire up the generated sequence into the BRAM address ports, and returned
  // read data into the DPA inputs
  for(i <- 0 to myP.getM()-1) {
    io.tilemem.lhs_req(i).addr := seqgen.seq.bits + (io.csr.lhsOffset)
    io.tilemem.lhs_req(i).writeEn := Bool(false)
    dpa.a(i) := io.tilemem.lhs_rsp(i).readData
    //printf("Read data from BRAM %d = %x\n", UInt(i), io.tilemem.lhs_rsp(i).readData)
    /*when(seqgen.seq.valid) {
      printf("LHS BRAM %d read addr %d\n", UInt(i), io.tilemem.lhs_req(i).addr)
      printf("Seqgen: %d offset: %d\n", seqgen.seq.bits, io.csr.lhsOffset)
    }*/
  }
  for(i <- 0 to myP.getN()-1) {
    io.tilemem.rhs_req(i).addr := seqgen.seq.bits + (io.csr.rhsOffset)
    io.tilemem.rhs_req(i).writeEn := Bool(false)
    dpa.b(i) := io.tilemem.rhs_rsp(i).readData
    /*when(seqgen.seq.valid) {
      printf("RHS BRAM %d read addr %d\n", UInt(i), io.tilemem.rhs_req(i).addr)
      printf("Seqgen: %d offset: %d\n", seqgen.seq.bits, io.csr.rhsOffset)
    }*/
  }
  // no backpressure in current design, so always pop
  seqgen.seq.ready := Bool(true)
  // data to DPA is valid after read from BRAM is completed
  val read_complete = ShiftRegister(seqgen.seq.valid, myP.getBRAMReadLatency())
  dpa.valid := read_complete
  dpa.shiftAmount := io.csr.shiftAmount
  dpa.negate := io.csr.negate
  // FIXME: this is not a great way to implement the accumulator clearing. if
  // there is a cycle of no data from the BRAM (due to a SequenceGenerator bug
  // or some weird timing interaction) an erronous accumulator clear may be
  // generated here.
  when(io.csr.clear_before_first_accumulation) {
    // set clear_acc to 1 for the very first cycle
    dpa.clear_acc := read_complete & !Reg(next=read_complete)
  } .otherwise {
    dpa.clear_acc := Bool(false)
  }

  // generate result memory write signal
  val time_to_write = myP.dpaLatency + myP.myLatency_read
  val do_write = ShiftRegister(io.start & seqgen.finished & io.csr.writeEn, time_to_write)
  val do_write_pulse = do_write & !Reg(next=do_write)
  // wire up DPA accumulators to resmem write ports
  for {
    i <- 0 until myP.getM()
    j <- 0 until myP.getN()
  } {
    io.res.req(i)(j).writeData := dpa.out(i)(j)
    io.res.req(i)(j).addr := io.csr.writeAddr
    io.res.req(i)(j).writeEn := do_write_pulse
  }
  /*
  when(io.start & !Reg(next=io.start)) {
    printf("Now executing:\n")
    printf(io.csr.printfStr, io.csr.printfElems():_*)
  }
  */
  /*
  when(do_write_pulse) {
    printf("Exec stage writing into resmem %d:\n", io.csr.writeAddr)
    for {
      i <- 0 until myP.getM()
      j <- 0 until myP.getN()
    } {
      printf("%d ", dpa.out(i)(j))
    }
    printf("\n")
  }
  */
}

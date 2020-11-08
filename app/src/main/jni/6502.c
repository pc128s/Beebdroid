/*
 * 6502 CPU emulation (C version)
 *
 * Written by Reuben Scratton.
 *
 * Originally by Tom Walker
 *
 */

#include "main.h"
#include <sys/time.h>
#include <errno.h>


M6502 acpu;
M6502* the_cpu = &acpu;

uint8_t ram_fe30,ram_fe34;
uint8_t* roms;


uint8_t acccon;
int otherstuffcount=0;
int romsel;
int FEslowdown[8]={1,0,1,1,0,0,1,0};

static int s_logflag = 0;
FILE* s_file = NULL;
static int framecurrent = -1;

#define LOGGING 1
// #define LOGCPU_ABORT_FRAME -1 // never
#define LOGCPU_ABORT_FRAME 20 // immediately after boot

void LOGF(char* format, ...) {
#if LOGGING
	char buff[512];
	va_list args;
	va_start (args, format);

  	if (!s_file || framecurrent != framecount) {
  	    if (s_file) fclose(s_file);
        char* fmt = "/storage/emulated/0/Android/data/com.littlefluffytoys.beebdroid/files/6502_%s.%03i.log";
        sprintf(buff, fmt, architecture, framecurrent = framecount);
        LOGI("fopen - %s", buff);
        s_file = fopen(buff, "w+");
	}

  	if (framecount == LOGCPU_ABORT_FRAME) { // Booted and key press checks
  	    fputs("SHOULD BE BOOTED - MAGICALLY ABORTING AT FRAME 20\n", s_file);
  	    fclose(s_file);
  		LOGI("SHOULD HAVE BOOTED BY NOW - aborting...");
  		abort();
  	}

	vsprintf (buff,format, args);

    if (s_file) {
        fputs(buff, s_file);
        fflush(s_file);
        //fclose(file);
    } else {
    	LOGI("fopen failed - errno= %i %s", errno, strerror(errno));
    	//LOGI("%s", buff);
    }
    va_end (args);
#endif
}
static int last_cycles;
static struct timeval tval_1, tval_0, tval_diff;
static int tval_first=1;
void log_cpu_C(M6502* cpu) {
#if LOGGING
// stx fef2 <- 0f , readmem esp 8 -> 4
//    int mfc = 12 ; int mcy = 4500 ;
// cli , push ax -> eax
//    int mfc = 14 ; int mcy = 17168 ;
 //   int mfc = 14000 ; int mcy = 0 ;
//    int mfc = 0 ; int mcy = 50000 ;
//    if (framecount < mfc) return;
//    if (framecount == mfc && cpu->cycles > mcy) return;
    if (tval_first) {
        gettimeofday(&tval_0, NULL);
        tval_first = 0;
    }
    gettimeofday(&tval_1, NULL);
    timersub(&tval_1, &tval_0, &tval_diff);

	unsigned char* p = cpu->mem;
	char buff[256];
	int i;

	// Checksum RAM
	unsigned int sum = 0;
	for (i=0 ; i<65536 ; i++) {
		sum += cpu->mem[i];
	}
    LOGF("lcpuC PC:%04X (%02X %02X %02X) A:%02X X:%02X Y:%02X P:%02X S:%02X "
         "int:%08x take:%08x "
         "mem:%08X %i:%i "
      //   "tv:%ld.%06ld "
         "%s\n",
          cpu->pc, p[cpu->pc],p[cpu->pc+1],p[cpu->pc+2], cpu->a, cpu->x, cpu->y, cpu->p, cpu->s,
          cpu->interrupt, cpu->takeint,
          sum, framecount, cpu->cycles,
      //    (long int)tval_diff.tv_sec, (long int)tval_diff.tv_usec,
          ops[p[cpu->pc]]);

//    LOGI("acpu@%X PC:%04X (%02X %02X %02X) A:%02X X:%02X Y:%02X P:%02X S:%02X\n",
//          cpu, cpu->pc, p[cpu->pc],p[cpu->pc+1],p[cpu->pc+2], cpu->a, cpu->x, cpu->y, cpu->p, cpu->s);
   // LOGI("PC:%04X (%02X %02X %02X) A:%02X X:%02X Y:%02X P:%02X S:%02X (%i/%i+%i)\n",
     //     cpu->pc, p[cpu->pc],p[cpu->pc+1],p[cpu->pc+2], cpu->a, cpu->x, cpu->y, cpu->p, cpu->s, framecount, cpu->cycles, last_cycles - cpu->cycles);
       //   last_cycles = cpu->cycles;

     if (tval_diff.tv_sec>1 || tval_diff.tv_usec>10000) {
        LOGI("Prior instruction took %ld.%06ld (%i/%i  pc ~%02X)\n",  (long int)tval_diff.tv_sec, (long int)tval_diff.tv_usec, framecount, cpu->cycles, cpu->pc);
     }

     gettimeofday(&tval_0, NULL);
#endif
}

void log_c_fn_(uint8_t op, void* table, void* fn, M6502* cpu) {
#if LOGGING
// 0x4B - 75 - asr  = !6666=&6060604b ; call 6666
	LOGI("C here! op=%02x table=%8p (%8p, %8p) fn=%8p cpu=%8p the_cpu=%8p",
			      op,     table,     &fns, the_cpu->c_fns, fn, cpu, the_cpu);
// Why is table != fns?
// C here! op=1a table=00000000 (cbcf0028) fn=fe88fe2c cpu=cbd517c8 the_cpu=cbd517c8
#endif
}
//extern void* opasm_lda_imm();
void log_asm(void* x0, void* x1, void* x2, void* x3) {
#if LOGGING
    gettimeofday(&tval_1, NULL);
    timersub(&tval_1, &tval_0, &tval_diff);
	//LOGI("here! %08x %ld.%06ld (cycle %i  pc ~%02X\n", v,  (long int)tval_diff.tv_sec, (long int)tval_diff.tv_usec, cpu->cycles, cpu->pc);
	LOGF("lasm! %08p %08p %08p %08p "
	    "tv:%ld.%06ld "
	     "(cycle %i  pc:%02X p:%02x s:%x02 "
         "i:%i ti:%i nmi:%i\n",
         x0, x1, x2, x3,
        (long int)tval_diff.tv_sec, (long int)tval_diff.tv_usec,
         the_cpu->cycles, the_cpu->pc, the_cpu->p, the_cpu->s,
         the_cpu->interrupt, the_cpu->takeint, the_cpu->nmi
         );
#endif
}

void log_undef_opcode_C_arm(uint8_t op, void* tab, int off, M6502* cpu) {
	LOGI("Undefined opcode! cpu=%p the_cpu=%p op=%02x off=%08x tab=%p", cpu, the_cpu, op, off, tab);
	LOGI("Undefined opcode! mem=%p [ pc=%04x ]", cpu->mem, cpu->pc );
	LOGI("Undefined opcode! mem=%p [ pc=%04x ] = %02x", cpu->mem, cpu->pc, cpu->mem[cpu->pc]);
	exit(1);
}

void log_undef_opcode_C_x86(M6502* cpu) {
	LOGI("Undefined opcode! cpu=%p the_cpu=%p", cpu, the_cpu);
	LOGI("Undefined opcode! mem=%p [ pc=%04x ]", cpu->mem, cpu->pc );
	LOGI("Undefined opcode! mem=%p [ pc=%04x ] = %02x", cpu->mem, cpu->pc, cpu->mem[cpu->pc]);
	exit(1);
}


#define readmem(x)  (((x)<0xfe00) ? cpu->mem[x] : readmem_ex(x))
static inline uint16_t readwordZp(uint8_t x, M6502* cpu) { return (*((uint16_t*)&(cpu->mem[x]))); }
#define readword(x) (((x)<0xfe00) ? (*((uint16_t*)&(cpu->mem[x]))) : (readmem_ex(x) | (readmem_ex(x+1)<<8)))
#define readwordpc() readword(cpu->pc); cpu->pc+=2
#define writemem(x, val) if (((uint16_t)x)<0x8000u) cpu->mem[x]=val; else writemem_ex(x, val)
///#define setzn(v) cpu->p_z=!(v); cpu->p_n=(v)&0x80
#define SET_FLAG(flag,val) if (val) cpu->p |= flag; else cpu->p &= ~flag
#define setzn(v) if(v) cpu->p &=~FLAG_Z; else cpu->p|=FLAG_Z;  if ((v)&0x80) cpu->p|=FLAG_N; else cpu->p&=~FLAG_N;
#define push(v) cpu->mem[0x100+(cpu->s--)]=v
#define pull()  cpu->mem[0x100+(++cpu->s)]

uint8_t readmem_ex_real(uint16_t addr);

uint8_t read6850acia(uint16_t addr);

uint8_t readSerialULA(uint16_t addr);

void write6850acia(uint32_t addr, uint8_t val);

void writeSerialULA(uint32_t addr, uint8_t val);

uint8_t readmem_ex(uint16_t addr)
{
	uint8_t rv = readmem_ex_real(addr);
	LOGF("readmem_ex! addr=%04X val=%02X\n", addr, rv);

	return rv;
}

uint8_t statusSerialULA = 0;

uint8_t status6850=2; // start ready for new data
uint8_t control6850=0;
int rxChar=-1;
int txChar=-1;

uint8_t readmem_ex_real(uint16_t addr) {
	if ( 0xFE08 <= addr && addr <= 0xFE1F) {
		LOGF("Reading %04X! rx %04X tx %04X ctl %02X sta %02X int %02X\n", addr, rxChar, txChar, control6850, status6850, the_cpu->interrupt);
	}

//	LOGI("readmem_ex! %04X", addr);
        uint8_t temp;
        switch (addr & ~3)
        {
                case 0xFE00: case 0xFE04: return readcrtc(addr);
                case 0xFE08: case 0xFE0C: return read6850acia(addr); // RS423+TAPE data 0xFE0A - 0xFE0F: also 6850, nominally
                case 0xFE10: return readSerialULA(addr); // Serial ULA  0xFE11-1F : also serial ULA
                //case 0xFE18: if (MASTER) return readadc(addr); break;
                case 0xFE40: case 0xFE44: case 0xFE48: case 0xFE4C:
                case 0xFE50: case 0xFE54: case 0xFE58: case 0xFE5C:
					temp=readsysvia(addr);
					return temp;
                case 0xFE60: case 0xFE64: case 0xFE68: case 0xFE6C:
                case 0xFE70: case 0xFE74: case 0xFE78: case 0xFE7C:
                	temp=readuservia(addr);
                	return temp;
                case 0xFE80: case 0xFE84: case 0xFE88: case 0xFE8C:
                case 0xFE90: case 0xFE94: case 0xFE98: case 0xFE9C:
                	//if (!MASTER) {
                        //if (WD1770) return read1770(addr);
                        return read8271(addr);
                	//}
                	break;
                case 0xFEC0: case 0xFEC4: case 0xFEC8: case 0xFECC:
                case 0xFED0: case 0xFED4: case 0xFED8: case 0xFEDC:
                	/*if (!MASTER)*/ return readadc(addr);
                	break;
                case 0xFEE0: case 0xFEE4: case 0xFEE8: case 0xFEEC:
                case 0xFEF0: case 0xFEF4: case 0xFEF8: case 0xFEFC:
                	break;
        }
        if (addr>=0xFC00 && addr<0xFE00) return 0xFF;
        //if (addr>=0xFe00 && addr<0xFf00) return addr>>8;
        //LOGI("reading from 0x%04X", addr);
        return the_cpu->mem[addr]; //
}

uint8_t readSerialULA(uint16_t addr) {
//	LOGI("6850 [a=%04x] read %02x\n", addr, statusSerialULA);
    // baud rate tx0,1,2/rx3,4,5 6-0=TAPE or 1=RS423, 7=motor
	return statusSerialULA;
}

void writeSerialULA(uint32_t addr, uint8_t val) {
//	LOGI("6850 [a=%04x] = %02x\n", addr, val);
	statusSerialULA = val;
	if (statusSerialULA & 64) {
		status6850 &= ~2; // always low when rs423 selected
	}
}

#define RS423_BUF 256
uint_t kbdBuf[RS423_BUF];
int kbdLastRead=0;
int kbdLastWrite=0;
int kbdDelay=0; // Avoid swamping beeb
uint_t pntBuf[RS423_BUF];
int pntLastRead=0;
int pntLastWrite=0;

int beebPrintedRS423(jint len, jbyte* bytes) {
	if (len == 0) { return RS423_BUF; }

	int ix = 0;
	while (ix < len) {
		if (pntLastRead == pntLastWrite) break; // Empty
		pntLastRead = (pntLastRead + 1) % RS423_BUF;
		bytes[ix++] = pntBuf[pntLastRead];
	}

	return ix;
}

void pollNextRS423Printer() {
	status6850 &= ~8u; // Always Clear To Send - external input
	if (status6850 & 2u) {
		return; // already removed, nothing new from processor.
	}

	int nextWrite = (pntLastWrite + 1) % RS423_BUF;
	if (pntLastRead == nextWrite) return; // Writing would cause 'empty', so wait.

	int taken = txChar;
	txChar = -1;
	status6850 |= 2u; //  High now, because we've taken the char or 'taken' -1
	if (taken != -1) {
		pntLastWrite = (pntLastWrite+1)%RS423_BUF;
		pntBuf[pntLastWrite] = taken;
		if ((control6850 & 96u) == 64) { // interrupt enabled?
			status6850 |= 128u; //  we're causing an interrupt because we've taken the char
			the_cpu->interrupt |= 8u; // interrupt
		}
	}
//	LOGI("6850 offering tak%02x c%02x s%02x i%02x\n", taken, control6850, status6850, the_cpu->interrupt);	return taken;
}

void write6850acia(uint32_t addr, uint8_t val) {
	switch (addr) {
		case 0xFE08:
	// Control - write-only
	// - b0,1 - counter divide 00-1,01-16,10-64,11-master_reset
	// - b2,3,4 - 4databits-0=7,1=8; 3stopbits-1=1, 0=2*, 2parity-0=even* 1=odd* but *100=8bit2stop, *101=8bits1stop
	// - b5,6 - tx ctl - 5-0=txint disabled, 6-0=RTS low, 10=RTS-high,txi enabled, 11=RTSlow, tx break level, txi disabled
	// - b7 - receive interrupt enable=1
	// Seen so far:
	// First:   03 = master reset
	// Second:  56 = 0101.0110 = 64/ 8bits1stop, rts high, tx int disabled
	// On VDU2: 36 = 0011.0110 = 64/ 8bits1stop, rts low, tx int enabled
			control6850 = val;
			if ((control6850 & 3u) == 3) {
				// What should 'master reset do?
				status6850=2; // Guess clear everything and indicate ready send a byte to printer (=2) AND to be given a byte, no errors, no int = 0.
			}
			if ((control6850 & 96u) == 32 && (status6850 & 2u) && txChar != -1)
				the_cpu->interrupt |= 8u;
			else
				the_cpu->interrupt &= ~8u;
			if ((control6850 & 128u) && (status6850 & 1u) && rxChar != -1)
				the_cpu->interrupt |= 4u;
			else
				the_cpu->interrupt &= ~4u;
//			LOGI("6850 [a=%04x] = %02x (c%02x s%02x i%02x)\n", addr, val, control6850, status6850, the_cpu->interrupt);
			return;
		case 0xFE09: // tx byte
			txChar = val;
			if (rxChar == -1)
				status6850 &= ~128u; // Apparently a read or write clears IRQ, but what if we send while an rx is pending?
			status6850 &= ~2u; // Write to tx sets status to 'full/low' until accepted by recipient
//			LOGI("6850 [a=%04x] = %02x (%02x)\n", addr, val, status6850);
			return;
		default:
//			LOGI("6850 [a=%04x] = %02x\n", addr, val)
		;
	}
}

jint beebAcceptedRS423(jint len, jbyte* bytes) {
	if (len == 0) {
		return RS423_BUF - (kbdLastWrite + RS423_BUF - kbdLastRead) % RS423_BUF; // Remaining capacity - don't convert a huge string to bytes!
	}

	int ix = 0;
	while(ix < len) {
		int kw = (kbdLastWrite + 1) % RS423_BUF;
		if (kw == kbdLastRead) break; // Equality means 'empty' so don't cause it.
		kbdBuf[kbdLastWrite = kw] = bytes[ix++];
	}

	return ix;
}

void pollNextRS423Keyboard() {
	if (status6850 & 1u) return; // that char not accepted yet, try again later
	if (kbdDelay > 0) { // Enforce minimum wait
		kbdDelay--;
		return;
	}
	if (kbdLastWrite == kbdLastRead) {
		return;
	}

	kbdLastRead = (kbdLastRead + 1) % RS423_BUF;
	rxChar = kbdBuf[kbdLastRead];
	status6850 |= 1u;   // Rx is full! Magically cleared by next processor read
	if (control6850 & 128u) {
		the_cpu->interrupt |= 4u; // Presume each system has its own bit - CPU just cares about nonzero
		status6850 |= 128u; // Rx is full! We caused an interrupt
	}
	kbdDelay=50; // 50 seemed safe, 10 wedged. What's good?
}

uint8_t read6850acia(uint16_t addr) {
	switch (addr) {
		case 0xFE08:  // Status - read only
			// - b0=RxFull cleared by processor read from Rx
			// - b1=TxEmpty low=full high=ready for processor to write to Tx
			// - b2=DCD - always low when RS423 is selected, high=carrier missing from cassette input
			// - b3=CTS - clear to send. always low for cassette, high if RS423 recipient is unready
			// - b4=FrameError - received char was malformed start/stop/parity
			// - b5=overrun - characters received but not read
			// - b6=parity error
			// - b7=IRQ bit high=serial issued IRQ (line is low), cleared by read from Rx or write to Tx
//			if ((the_cpu->interrupt & ~1u) != 0)
//			    LOGI("6850 [a=%04x] read %02x (tx%02x c%02x xi%02x)\n", addr, status6850, txChar, control6850, the_cpu->interrupt & ~1u /* ignore ifr */); // We're polled frequently to eliminate b7
			pollNextRS423Keyboard();
			pollNextRS423Printer();
			return status6850;
		case 0xFE09:  // Rx=read
			if (rxChar != -1 && (status6850 & 1u)) {
				uint8_t taken = rxChar;
				rxChar = -1;
				status6850 &= ~1u; // Magic clear of RxFull
				if (txChar == -1)
					status6850 &= ~128u; // Apparently a read or write clears IRQ, but what if we send while a tx is pending?
//				LOGI("6850 [a=%04x] read %02x (st:%02x)\n", addr, taken, status6850);
				return taken;
			}
			return rxChar;
		default:
//			LOGI("6850 [a=%04x] read default\n", addr);
			return 0;
	}
}

uint16_t readword_ex(uint16_t addr)
{
	return readmem_ex(addr) | (readmem_ex(addr+1)<<8);
}

#ifdef _ARM_
void writemem_ex(uint16_t addr, uint8_t val16)
#else
void writemem_ex(uint32_t addr, uint32_t val16)
#endif
{
int lgd = -1;
uint8_t val = (val16) & 0xff;
addr &= 0xffff;
if ( 0xFE08 <= addr && addr <= 0xFE1F) {
//	LOGF("Writing %02X(%04X) to %04X! rx %04X tx %04X ctl %02X sta %02X int %02X\n", val, val16, addr, rxChar, txChar, control6850, status6850, the_cpu->interrupt);
	lgd = val;
}
//LOGF("writemem_ex! addr=%04X val=%02X\n", addr, val);

	int c;
	if (addr<0xFC00 || addr>=0xFF00) return;
	//PUT BACK SOMEDAY if (addr<0xFE00 || FEslowdown[(addr>>5)&7]) { if (cycles&1) {polltime(2);} else { polltime(1); } }
	switch (addr & ~3)
	{
			case 0xFE00: case 0xFE04: writecrtc(addr,val); break;
		case 0xFE08: case 0xFE0C: write6850acia(addr,val); break;
		case 0xFE10: case 0xFE14: writeSerialULA(addr, val); break;
			//case 0xFE18: if (MASTER) writeadc(addr,val); break;
			case 0xFE20: writeula(addr,val); break;
			//case 0xFE24: if (MASTER) write1770(addr,val); else writeula(addr,val); break;
			//case 0xFE28: if (MASTER) write1770(addr,val); break;
			case 0xFE30:
				ram_fe30=val;
				if (romsel != (val&15)) {
					romsel = val&15;
					//LOGI("ROMSEL set to %d", romsel);
					memcpy(the_cpu->mem+0x8000, roms+(romsel*16384), 16384);
				}
				break;
			case 0xFE34:
				ram_fe34=val;
				break;
			case 0xFE40: case 0xFE44: case 0xFE48: case 0xFE4C:
			case 0xFE50: case 0xFE54: case 0xFE58: case 0xFE5C:
				writesysvia(addr,val);
				break;
			case 0xFE60: case 0xFE64: case 0xFE68: case 0xFE6C:
			case 0xFE70: case 0xFE74: case 0xFE78: case 0xFE7C:
				writeuservia(addr,val);
				break;
			case 0xFE80: case 0xFE84: case 0xFE88: case 0xFE8C:
			case 0xFE90: case 0xFE94: case 0xFE98: case 0xFE9C:
//                        break;
//			if (!MASTER)
			{
					/*if (WD1770) write1770(addr,val);
					else*/        write8271(addr,val);
			}
			break;
			case 0xFEC0: case 0xFEC4: case 0xFEC8: case 0xFECC:
			case 0xFED0: case 0xFED4: case 0xFED8: case 0xFEDC:
				/*if (!MASTER)*/ writeadc(addr,val);
				break;
			case 0xFEE0: case 0xFEE4: case 0xFEE8: case 0xFEEC:
			case 0xFEF0: case 0xFEF4: case 0xFEF8: case 0xFEFC:
				// writetubehost(addr,val);
				break;
	}
#if LOGGING
if (lgd > -1) {
	LOGI("written %02X to %04X! pc=%X cycle=%i", val, addr, the_cpu->pc, the_cpu->cycles);
}
#endif
}


void reset6502()
{
	int c;
	the_cpu->cycles=0;
	the_cpu->pc=*(uint16_t*)&(the_cpu->mem[0xfffc]);
	the_cpu->p |= FLAG_I;
	the_cpu->nmi =0;
	//output=0;
	LOGI("\n\n6502 RESET! PC=%X\n", the_cpu->pc);
}



void adc_bcd_C(M6502* cpu, uint8_t temp) {
	LOGI("Doing ADC BCD! %d", temp);
	register int ah=0;
	register uint8_t tempb = cpu->a+temp+((cpu->p & FLAG_C)?1:0);
	if (!tempb)
	   cpu->p |= FLAG_Z;
	register int al=(cpu->a&0xF)+(temp&0xF)+((cpu->p & FLAG_C)?1:0);
	if (al>9) {
		al-=10;
		al&=0xF;
		ah=1;
	}
	ah+=((cpu->a>>4)+(temp>>4));
	if (ah&8)
		cpu->p |= FLAG_N;
	SET_FLAG(FLAG_V, (((ah << 4) ^ cpu->a) & 128) && !((cpu->a ^ temp) & 128));
	cpu->p &= ~FLAG_C;
	if (ah>9) {
		cpu->p |= FLAG_C;
		ah-=10;
		ah&=0xF;
	}
	cpu->a=(al&0xF)|(ah<<4);
}

void sbc_bcd_C(M6502* cpu, uint8_t temp) {
	register int hc6=0;
	cpu->p &= ~(FLAG_Z | FLAG_N);
	if (!((cpu->a-temp)-((cpu->p & FLAG_C)?0:1)))
	   cpu->p |= FLAG_Z;
	register int al=(cpu->a&15)-(temp&15)-((cpu->p & FLAG_C)?0:1);
	if (al&16) {
		al-=6;
		al&=0xF;
		hc6=1;
	}
	register int ah=(cpu->a>>4)-(temp>>4);
	if (hc6) ah--;
	if ((cpu->a-(temp+((cpu->p & FLAG_C)?0:1)))&0x80)
	   cpu->p |= FLAG_N;
	SET_FLAG(FLAG_V, (((cpu->a-(temp+((cpu->p & FLAG_C)?0:1)))^temp)&128)&&((cpu->a^temp)&128));
	cpu->p |= FLAG_C;
	if (ah&16)  {
		cpu->p &= ~FLAG_C;
		ah -= 6;
		ah &= 0xF;
	}
	cpu->a=(al&0xF)|((ah&0xF)<<4);
}



#define SBC(temp) \
	if (!(cpu->p & FLAG_D)) { \
		register uint8_t c=(cpu->p & FLAG_C)?0:1; \
		register uint16_t tempw=cpu->a-(temp+c);    \
		register int tempv=(short)cpu->a-(short)(temp+c);            \
		SET_FLAG(FLAG_V, ((cpu->a ^ (temp+c)) & (cpu->a ^ (uint8_t)tempv)&0x80)); \
		SET_FLAG(FLAG_C, tempv>=0);\
		cpu->a=tempw&0xFF;              \
		setzn(cpu->a);                  \
	}                                  \
	else {                                  \
		register int hc6=0;                               \
		cpu->p &= ~(FLAG_Z | FLAG_N);                            \
		if (!((cpu->a-temp)-((cpu->p & FLAG_C)?0:1)))            \
		   cpu->p |= FLAG_Z;                             \
		   register int al=(cpu->a&15)-(temp&15)-((cpu->p & FLAG_C)?0:1);      \
		if (al&16) {                                   \
				al-=6;                      \
				al&=0xF;                    \
				hc6=1;                       \
		}                                   \
		register int ah=(cpu->a>>4)-(temp>>4);                \
		if (hc6) ah--;                       \
		if ((cpu->a-(temp+((cpu->p & FLAG_C)?0:1)))&0x80)        \
		   cpu->p |= FLAG_N;                             \
		SET_FLAG(FLAG_V, (((cpu->a-(temp+((cpu->p & FLAG_C)?0:1)))^temp)&128)&&((cpu->a^temp)&128)); \
		cpu->p |= FLAG_C; \
		if (ah&16)  { \
			cpu->p &= ~FLAG_C; \
			ah-=6;                      \
			ah&=0xF;                    \
		}                                   \
		cpu->a=(al&0xF)|((ah&0xF)<<4);                 \
	}

FN fns[];
/*
int callcounts[256];
void dump_callcounts() {
	int i;
	for (i=0 ; i<256 ; i++) {
		LOGI("count,%02X,%d", i, callcounts[i]);
	}
	memset(callcounts, 0, sizeof(callcounts));
}*/


int vidclockacc=0;
int abcd=0;
// int k = 18561; // Y6R
//int k = 20250; // YFR
//int k = 20500; // YF6R on x86, just Y on 86_64 but not under logging
int k = 90500; // YF6R on x86, just Y on 86_64
int m = 650000;
extern uint8_t keys[16][16];

void do_poll_C(M6502* cpu, int c) {
    int d = 0;
//    if (abcd++>m && abcd<m + k*8 - 1) {
// Repeatably fake keys
//        d = (abcd -m ) / k;
//        keys[((d & 2) == 0) + 3][((d & 4) == 0) + 3] = (d & 1) == 0;
//    }

//    LOGF("do_poll c %x abcd=%d d=%x int=%x take=%x\n", c, abcd, d, cpu->interrupt, cpu->takeint);

	if (otherstuffcount<=0) {
		otherstuffcount+=128;
		logvols();
		if (motorspin) {
			motorspin--;
			if (!motorspin) fdcspindown();
		}
	}

	// Time to poll hardware?
	if (c > 0) {
		/*vidclockacc += c;
		if (vidclockacc>=128) {
			pollvideo(vidclockacc);
			vidclockacc=0;
		}*/
//log_asm(0xAB);
		pollvideo(c); // Takes 10ms every 6 instrutions from frame 14?
//log_asm(0xAC);
		sysvia.t1c -= c;
		if (!(sysvia.acr&0x20))  sysvia.t2c-=c;
		if (sysvia.t1c<-3  || sysvia.t2c<-3)  updatesystimers();
		uservia.t1c-=c;
		if (!(uservia.acr&0x20)) uservia.t2c-=c;
		if (uservia.t1c<-3 || uservia.t2c<-3) updateusertimers();
		otherstuffcount-=c;
//log_asm(0xAD);
		if (motoron) {
			if (fdctime) {
				fdctime-=c;
				if (fdctime<=0) fdccallback();
			}
			disctime-=c;
			if (disctime<=0) {
				disctime+=16; disc_poll();
			}
		}
	}
//log_asm(0xAF);

}




int fn_arr(M6502* cpu) {
	cpu->a&=readmem(cpu->pc); cpu->pc++;
	register int tempi=cpu->p & FLAG_C;
	if (cpu->p & FLAG_D) // This instruction is just as broken on a real 6502 as it is here
	{
		SET_FLAG(FLAG_V, ((cpu->a>>6)^(cpu->a>>7))); // V set if bit 6 changes in ROR
		cpu->a>>=1;
			if (tempi) cpu->a|=0x80;
			setzn(tempi);
		    cpu->p &= ~FLAG_C;
			if ((cpu->a&0xF)+(cpu->a&1)>5) cpu->a=(cpu->a&0xF0)+((cpu->a&0xF)+6); // Do broken fixup
			if ((cpu->a&0xF0)+(cpu->a&0x10)>0x50) { cpu->a+=0x60; cpu->p |= FLAG_C; }
	}
	else
	{       //V & C flag behaviours in 64doc.txt are backwards
		SET_FLAG(FLAG_V, ((cpu->a>>6)^(cpu->a>>7))); //V set if bit 6 changes in ROR
		cpu->a>>=1;
		if (tempi) cpu->a|=0x80;
		setzn(cpu->a);
		SET_FLAG(FLAG_C, cpu->a&0x40);
	}
	return 2;
}
int fn_brk(M6502* cpu) {
	//LOGI("BRK! at %04X", cpu->pc);
	cpu->pc++;
	push(cpu->pc>>8);
	push(cpu->pc&0xFF);
	register uint8_t temp=0x30 | cpu->p;
	push(temp);
	cpu->pc=*(uint16_t*)&(cpu->mem[0xfffe]);
	cpu->p |= FLAG_I;
	cpu->takeint=0;
	return -7;
}




int fn_asr_imm(M6502* cpu) { // 0x4B - asr
	cpu->a &= readmem(cpu->pc); cpu->pc++;
	SET_FLAG(FLAG_C, cpu->a & 1);
	cpu->a >>= 1;
	setzn(cpu->a);
	return 2;
}



int fn_anc_imm(M6502* cpu) {
//	uint8_t *p = cpu->mem;
//	LOGF("fn_anc PC:%04X (%02X %02X %02X) A:%02X X:%02X Y:%02X P:%02X S:%02X mem:%08X %i:%i %ld.%06ld %s\n",
//		 cpu->pc, p[cpu->pc],p[cpu->pc+1],p[cpu->pc+2],
//		                           cpu->a, cpu->x, cpu->y, cpu->p, cpu->s, p, framecount, cpu->cycles,  (long int)tval_diff.tv_sec, (long int)tval_diff.tv_usec, ops[p[cpu->pc]]);
	cpu->a &= readmem(cpu->pc);
	cpu->pc++;
	setzn(cpu->a);
	SET_FLAG(FLAG_C, cpu->p & FLAG_N);
	return 2;
}


int fn_sre_x(M6502* cpu) {
	register uint8_t tint8=readmem(cpu->pc)+cpu->x; cpu->pc++;
	register uint16_t addr=readwordZp(tint8, cpu);
	tint8=readmem(addr);
	SET_FLAG(FLAG_C, tint8&1);
	tint8>>=1;
	writemem(addr,tint8);
	cpu->a ^= tint8;
	setzn(cpu->a);
	return 7;
}


int fn_sre_zp(M6502* cpu) {
	register uint16_t addr=readmem(cpu->pc); cpu->pc++;
	register uint8_t tint8=readmem(addr);
	SET_FLAG(FLAG_C, tint8&1);
	tint8>>=1;
	writemem(addr,tint8);
	cpu->a ^= tint8;
	setzn(cpu->a);
	return 5;
}

int fn_sre_abs(M6502* cpu) {
	register uint16_t addr=readwordpc();
	register uint8_t tint8=readmem(addr);
	SET_FLAG(FLAG_C, tint8&1);
	tint8>>=1;
	writemem(addr,tint8);
	cpu->a^=tint8;
	setzn(cpu->a);
	return 6;
}

int fn_sre_y(M6502* cpu) {
	register uint8_t tint8=readmem(cpu->pc); cpu->pc++;
	register uint16_t addr=readwordZp(tint8, cpu);
	register int clocks = 7;
	if ((addr&0xFF00)^((addr+cpu->y)&0xFF00))clocks++;
	tint8=readmem(addr+cpu->y);
	//?writemem(addr+cpu->y,tint8);
	SET_FLAG(FLAG_C, tint8&1);
	tint8>>=1;
	writemem(addr+cpu->y,tint8);
	cpu->a^=tint8;
	setzn(cpu->a);
	return clocks;
}


int fn_sre_zp_x(M6502* cpu) {
	register uint16_t addr=(readmem(cpu->pc)+cpu->x)&0xFF; cpu->pc++;
	register uint8_t tint8=readmem(addr);
	//writemem(addr,tint8);
    SET_FLAG(FLAG_C, tint8&1);
	tint8>>=1;
	writemem(addr,tint8);
	cpu->a^=tint8;
	setzn(cpu->a);
	return 5;
}


int fn_sre_abs_y(M6502* cpu) {
	register uint16_t addr=readwordpc();
	register int clocks = 6;
	if ((addr&0xFF00)^((addr+cpu->y)&0xFF00)) clocks++;
	register uint8_t tint8=readmem(addr+cpu->y);
	writemem(addr+cpu->y,tint8);
    SET_FLAG(FLAG_C, tint8&1);
	tint8>>=1;
	writemem(addr+cpu->y,tint8);
	cpu->a^=tint8;
	setzn(cpu->a);
	return clocks;
}


int fn_sre_abs_x(M6502* cpu) {
	register uint16_t addr=readwordpc();
	register int clocks = 6;
	if ((addr&0xFF00)^((addr+cpu->x)&0xFF00)) clocks++;
	register uint8_t tint8=readmem(addr+cpu->x);
	writemem(addr+cpu->x,tint8);
    SET_FLAG(FLAG_C, tint8&1);
	tint8>>=1;
	writemem(addr+cpu->x,tint8);
	cpu->a^=tint8;
	setzn(cpu->a);
	return clocks;
}


int fn_sax_x(M6502* cpu) {
	register uint8_t tint8=readmem(cpu->pc)+cpu->x; cpu->pc++;
	register uint16_t addr=readwordZp(tint8, cpu);
	writemem(addr,cpu->a&cpu->x);
	return 6;
}

int fn_sax_zp(M6502* cpu) {
	register uint16_t addr=readmem(cpu->pc); cpu->pc++;
	writemem(addr,cpu->a&cpu->x);
	return 3;
}

int fn_ane(M6502* cpu) {
	register uint8_t tint8=readmem(cpu->pc); cpu->pc++;
	cpu->a=(cpu->a|0xEE)&cpu->x&tint8; //Internal parameter always 0xEE on BBC, always 0xFF on Electron
	setzn(cpu->a);
	return 2;
}

int fn_sax_abs(M6502* cpu) {
	register uint16_t addr=readwordpc();
	writemem(addr,cpu->a&cpu->x);
	return 4;
}


int fn_sha_y(M6502* cpu) {
	register uint8_t tint8=readmem(cpu->pc); cpu->pc++;
	register uint16_t addr=readwordZp(tint8, cpu);
	writemem(addr+cpu->y,cpu->a&cpu->x&((addr>>8)+1));
	return 6;
}

int fn_sax_zp_y(M6502* cpu) {
	register uint16_t addr=readmem(cpu->pc); cpu->pc++;
	writemem((addr+cpu->y)&0xFF,cpu->a&cpu->x);
	return 4;
}

int fn_shs_abs_y(M6502* cpu) {
	register uint16_t addr=readwordpc();
	readmem((addr&0xFF00)+((addr+cpu->y)&0xFF));
	writemem(addr+cpu->y,cpu->a&cpu->x&((addr>>8)+1));
	cpu->s=cpu->a&cpu->x;
	return 5;
}

int fn_shy_abs_x(M6502* cpu) {
	register uint16_t addr=readwordpc();
	readmem((addr&0xFF00)+((addr+cpu->x)&0xFF));
	writemem(addr+cpu->x,cpu->y&((addr>>8)+1));
	return 5;
}

int fn_shx_abs_y(M6502* cpu) {
	register uint16_t addr=readwordpc();
	readmem((addr&0xFF00)|((addr+cpu->x)&0xFF));
	writemem(addr+cpu->y,cpu->x&((addr>>8)+1));
	return 5;
}

int fn_sha_abs_y(M6502* cpu) {
	register uint16_t addr=readwordpc();
	readmem((addr&0xFF00)|((addr+cpu->x)&0xFF));
	writemem(addr+cpu->y, cpu->a & cpu->x & ((addr>>8)+1));
	return 5;
}


int fn_lax_y(M6502* cpu) {
	register uint8_t tint8=readmem(cpu->pc)+cpu->x; cpu->pc++;
	register uint16_t addr=readwordZp(tint8, cpu);
	cpu->a=cpu->x=readmem(addr);
	setzn(cpu->a);
	return 6;
}

int fn_lax_zp(M6502* cpu) {
	register uint16_t addr=readmem(cpu->pc); cpu->pc++;
	cpu->a=cpu->x=readmem(addr);
	setzn(cpu->a);
	return 3;
}
int fn_lax(M6502* cpu) {
	register uint8_t tint8=readmem(cpu->pc); cpu->pc++;
	cpu->a=cpu->x=((cpu->a|0xEE)&tint8); //WAAAAY more complicated than this, but it varies from machine to machine anyway
	setzn(cpu->a);
	return 2;
}
int fn_lax_abs(M6502* cpu) {
	register uint16_t addr=readwordpc();
	cpu->a=cpu->x=readmem(addr);
	setzn(cpu->a);
	return 4;
}

int fn_lax_y2(M6502* cpu) {
	register uint8_t tint8=readmem(cpu->pc); cpu->pc++;
	register uint16_t addr=readwordZp(tint8, cpu);
	register int clocks = 5;
	if ((addr&0xFF00)^((addr+cpu->y)&0xFF00)) clocks++;
	cpu->a=cpu->x=readmem(addr+cpu->y);
	setzn(cpu->a);
	return clocks;
}
int fn_lax_zp_y(M6502* cpu) {
	register uint16_t addr=readmem(cpu->pc); cpu->pc++;
    cpu->a=cpu->x=readmem((addr+cpu->y)&0xFF);
	setzn(cpu->a);
	return 3;
}

int fn_las_abs_y(M6502* cpu) {
	register uint16_t addr=readwordpc();
	int cl = 4;
	if ((addr&0xFF00)^((addr+cpu->y)&0xFF00)) cl++;
    cpu->a= cpu->x = cpu->s = cpu->s & readmem(addr+cpu->y); //No, really!
	setzn(cpu->a);
	return cl;
}


int fn_lax_abs_y(M6502* cpu) {
	register uint16_t addr=readwordpc();
	int cl=4;
	if ((addr&0xFF00)^((addr+cpu->y)&0xFF00)) cl++;
    cpu->a=cpu->x=readmem(addr+cpu->y);
	setzn(cpu->a);
	return cl;
}
int fn_dcp_indx(M6502* cpu) {
	register uint8_t tint8=readmem(cpu->pc)+cpu->x; cpu->pc++;
	register uint16_t addr=readwordZp(tint8, cpu);
	tint8=readmem(addr);
	writemem(addr,tint8);
	tint8--;
	writemem(addr,tint8);
	setzn(cpu->a-tint8);
    SET_FLAG(FLAG_C, cpu->a>=tint8);
	return 7;
}

int fn_dcp_zp(M6502* cpu) {
	register uint16_t addr=readmem(cpu->pc); cpu->pc++;
	register uint8_t tint8=readmem(addr)-1;
	writemem(addr,tint8);
	setzn(cpu->a-tint8);
    SET_FLAG(FLAG_C, cpu->a>=tint8);
	return 5;
}

int fn_sbx_imm(M6502* cpu) {
	register uint8_t tint8=readmem(cpu->pc); cpu->pc++;
	setzn((cpu->a&cpu->x)-tint8);
    SET_FLAG(FLAG_C, (cpu->a&cpu->x)>=tint8);
    cpu->x=(cpu->a&cpu->x)-tint8;
	return 2;
}
int fn_dcp_abs(M6502* cpu) {
	register uint16_t addr=readwordpc();
	register uint8_t tint8=readmem(addr)-1;
	writemem(addr,tint8);
	setzn(cpu->a-tint8);
    SET_FLAG(FLAG_C, cpu->a>=tint8);
	return 6;
}

int fn_dcp_indy(M6502* cpu) {
	register uint8_t tint8=readmem(cpu->pc); cpu->pc++;
	register uint16_t addr=readwordZp(tint8, cpu);
	int clocks = 7;
	if ((addr&0xFF00)^((addr+cpu->y)&0xFF00)) clocks++;
	tint8=readmem(addr)-1;
	writemem(addr,tint8+1);
	writemem(addr,tint8);
	setzn(cpu->a-tint8);
    SET_FLAG(FLAG_C, cpu->a>=tint8);
	return clocks;
}

int fn_dcp_zp_x(M6502* cpu) {
	register uint16_t addr=(readmem(cpu->pc)+cpu->x)&0xFF; cpu->pc++;
	register uint8_t tint8=readmem(addr)-1;
	writemem(addr,tint8);
	setzn(cpu->a-tint8);
    SET_FLAG(FLAG_C, cpu->a>=tint8);
	return 5;
}

int fn_dcp_abs_y(M6502* cpu) {
	register uint16_t addr=readwordpc();
	int clocks = 6;
	if ((addr&0xFF00)^((addr+cpu->y)&0xFF00)) clocks++;
	register uint8_t tint8=readmem(addr+cpu->y)-1;
	//writemem(addr+cpu->y,tint8+1);
	writemem(addr+cpu->y,tint8);
	setzn(cpu->a-tint8);
    SET_FLAG(FLAG_C, cpu->a>=tint8);
	return clocks;
}

int fn_dcp_abs_x(M6502* cpu) {
	register uint16_t addr=readwordpc();
	int clocks = 6;
	if ((addr&0xFF00)^((addr+cpu->x)&0xFF00)) clocks++;
	register uint8_t tint8=readmem(addr+cpu->x)-1;
	//writemem(addr+cpu->x,tint8+1);
	writemem(addr+cpu->x,tint8);
	setzn(cpu->a-tint8);
    SET_FLAG(FLAG_C, cpu->a>=tint8);
	return clocks;
}

int fn_isb_indx(M6502* cpu) {
	register uint8_t tint8=readmem(cpu->pc)+cpu->x; cpu->pc++;
	register uint16_t addr=readwordZp(tint8, cpu);
	tint8=readmem(addr);
	tint8++;
	writemem(addr,tint8);
	SBC(tint8);
	return 7;
}

int fn_isb_zp(M6502* cpu) {
	register uint16_t addr=readmem(cpu->pc); cpu->pc++;
	register uint8_t tint8=readmem(addr);
	//?writemem(addr,tint8);
	tint8++;
	writemem(addr,tint8);
	SBC(tint8);
	return 5;
}

int fn_isb_abs(M6502* cpu) {
	register uint16_t addr=readwordpc();
	register uint8_t tint8=readmem(addr);
	tint8++;
	writemem(addr,tint8);
	SBC(tint8);
	return 6;
}


int fn_isb_indy(M6502* cpu) {
	register uint8_t tint8=readmem(cpu->pc); cpu->pc++;
	register uint16_t addr=readwordZp(tint8, cpu);
	int clocks = 7;
	if ((addr&0xFF00)^((addr+cpu->y)&0xFF00)) clocks++;
	tint8=readmem(addr+cpu->y);
	tint8++;
	writemem(addr+cpu->y,tint8);
	SBC(tint8);
	return clocks;
}


int fn_isb_zp_x(M6502* cpu) {
	register uint16_t addr=(readmem(cpu->pc)+cpu->x)&0xFF; cpu->pc++;
	register uint8_t temp=readmem(addr);
	temp++;
	writemem(addr,temp);
	SBC(temp);
	return 5;
}


int fn_isb_abs_y(M6502* cpu) {
	register uint16_t addr=readwordpc();
	addr += cpu->y;
	register uint8_t temp=readmem(addr);
	temp++;
	writemem(addr,temp);
	SBC(temp);
	return 7;
}


int fn_isb_abs_x(M6502* cpu) {
	register uint16_t addr=readwordpc();
	addr += cpu->x;
	register uint8_t temp=readmem(addr);
	temp++;
	writemem(addr,temp);
	SBC(temp);
	return 7;
}
FN fns[] = {
	fn_brk, 	// 0x00
	0, 	// 0x01  	ORA (,x)
	0,
	0,  // 0x03: Undocumented - SLO (,x)
	0,  // 0x04: Undocumented - NOP zp
	0,	// 0x05: ORA zp
	0,  // 0x06: ASL zp
	0,  // 0x07: Undocumented - SLO zp
	0,     // 0x08: PHP
	0, // 0x09: ORA imm
	0,   // 0x0A: ASL A
	fn_anc_imm, // 0x0B: Undocumented - ANC imm
	0, // 0x0C: Undocumented - NOP abs
	0, // 0x0D: ORA abs
	0, // 0x0E: ASL abs
	0, // 0x0F: Undocumented - SLO abs
	0,     // 0x10: BPL
	0,   // 0x11: ORA (),y
	0,
	0,   // 0x13: Undocumented - SLO (),y
	0,// 0x14: Undocumented - NOP zp,x
	0,// 0x15: ORA zp,x
	0,// 0x16: ASL zp,x
	0,// 0x17: Undocumented - SLO zp,x
	0,     // 0x18: CLC
	0,//0x19: ORA abs,y
	0,     // 0x1A: Undocumented - NOP
	0,//0x1B: Undocumented - SLO abs,y
	0,//0x1C: Undocumented - NOP abs,x
	0,//0x1D: ORA abs,x
	0,//0x1E: ASL abs,x
	0,//0x1F: Undocumented - SLO abs,x
	0,     // 0x20: JSR
	0,   // 0x21: AND (,x)
	0,
	0,	// 0x23: Undocumented - RLA (,x)
	0,	// 0x24: BIT zp
	0,  // 0x25: AND zp
	0,  // 0x26: ROL zp
	0,  // 0x27: Undocumented - RLA zp
	0,     // 0x28: PLP
	0,		// 0x29: AND
	0,   // 0x2A: ROL A
	fn_anc_imm, // 0x2B: Undocumented - ANC imm
	0, // 0x2C: BIT abs
	0, // 0x2D: AND abs
	0, // 0x2E: ROL abs
	0, // 0x2F: Undocumented - RLA abs
	0, 	// 0x30: BMI
	0,   // 0x31: AND (),y
	0,
	0,   // 0x33: Undocumented - RLA (),y
	0,// 0x34: Undocumented - NOP zp,x
	0,// 0x35: AND zp,x
	0,// 0x36: ROL zp,x
	0,// 0x37: Undocumented - RLA zp,x
	0, 	// 0x38: SEC
	0,//0x39: AND abs,y
	0,	    // 0x3A: Undocumented - NOP
	0,//0x3B: Undocumented - RLA abs,y
	0,//0x3C: Undocumented - NOP abs,x
	0,//0x3D: AND abs,x
	0,//0x3E: ROL abs,x
	0,//0x3F: Undocumented - RLA abs,x
	0,     // 0x40: RTI
	0,   // 0x41: EOR (,x)
	0,
	fn_sre_x,   // 0x43: Undocumented - SRE (,x)
	0,  // 0x44:
	0,  // 0x45: EOR zp
	0,  // 0x46: LSR zp
	fn_sre_zp,  // 0x47: Undocumented - SRE zp
	0,     // 0x48: PHA
	0, // 0x49: EOR imm
	0,   // 0x4A: LSR A
	fn_asr_imm, // 0x4B: Undocumented - ASR imm
	0,		// 0x4C: JMP
	0, // 0x4D: EOR abs
	0, // 0x4E: LSR abs
	fn_sre_abs, // 0x4F: Undocumented - SRE abs
	0,     // 0x50: BVC
	0,   // 0x51: EOR (),y
	0,
	fn_sre_y,   // 0x53: Undocumented - SRE (),y
	0,// 0x54:
	0,// 0x55: EOR zp,x
	0,// 0x56: LSR zp,x
	0,// 0x57: Undocumented - SRE zp,x
	0,     // 0x58: CLI
	0,//0x59: EOR abs,y
	0,      //0x5A: Undocumented - NOP
	fn_sre_abs_y,//0x5B: Undocumented - SRE abs,y
	0,//0x5C: Undocumented - NOP abs,x
	0,//0x5D: EOR abs,x
	0,//0x5E: LSR abs,x
	fn_sre_abs_x,//0x5F: Undocumented - SRE abs,x
	0, 	// 0x60: RTS
	0,   // 0x61: ADC (,x)
	0,
	0,   // 0x63: Undocumented - RRA (,x)
	0,  // 0x64: Undocumented - NOP zp
	0,  // 0x65: ADC zp
	0,  // 0x66: ROR zp
	0,  // 0x67: Undocumented - RRA zp
	0,     // 0x68: PLA
	0, // 0x69: ADC imm
	0,   // 0x6A: ROR A
	fn_arr,     // 0x6B: Undocumented - ARR
	0, // 0x6C: JMP ()
	0, // 0x6D: ADC abs
	0, // 0x6E: ROR abs
	0, // 0x6F: Undocumented - RRA abs
	0,     // 0x70: BVS
	0,   // 0x71: ADC (),y
	0,
	0,   // 0x73: Undocumented - RRA (,y)
	0,// 0x74: Undocumented - NOP zp,x
	0,// 0x75: ADC zp,x
	0,// 0x76: ROR zp,x
	0,// 0x77: Undocumented - RRA zp,x
	0,     // 0x78: SEI
	0,//0x79: ADC abs,y
	0,     // 0x7A: Undocumented - NOP
	0,//0x7B: Undocumented - RRA abs,y
	0,
	0,//0x7D: ADC abs,x
	0,//0x7E: ROR abs,x
	0,//0x7F: Undocumented - RRA abs,x
	0,  //0x80: Undocumented - NOP imm
	0,   // 0x81: STA (,x)
	0, // 0x82: Undocumented - NOP imm
	fn_sax_x,   // 0x83: Undocumented - SAX (,x)
	0,  // 0x84: STY zp
	0,  // 0x85: STA zp
	0,  // 0x86: STX zp
	fn_sax_zp,  // 0x87: Undocumented - SAX zp
	0,     // 0x88: DEY
	0, // 0x89: Undocumented - NOP imm
	0,     // 0x8A: TXA
	fn_ane,     // 0x8B: Undocumented - ANE
	0, // 0x8C: STY abs
	0, // 0x8D: STA abs
	0, // 0x8E: STX abs
	fn_sax_abs, // 0x8F: Undocumented - SAX abs
	0,     // 0x90: BCC
	0,   // 0x91: STA (),y
	0,
	fn_sha_y,   // 0x93: Undocumented - SHA (),y
	0,// 0x94: STY zp,x
	0,// 0x95: STA zp,x
	0,// 0x96: STX zp,y
	fn_sax_zp_y,// 0x97: Undocumented - SAX zp,y
	0,     // 0x98: TYA
	0,//0x99: STA abs,y
	0,     // 0x9A: TXS
	fn_shs_abs_y,//0x9B: Undocumented - SHS abs,y
	fn_shy_abs_x,//0x9C: Undocumented - SHY abs,x
	0,//0x9D: STA abs,x
	fn_shx_abs_y,//0x9E: Undocumented - SHX abs,y
	fn_sha_abs_y,//0x9F: Undocumented - SHA abs,y
	0, // 0xA0: LDY imm
	0,   // 0xA1: LDA (,x)
	0, // 0xA2: LDX imm
	fn_lax_y,   // 0xA3: Undocumented - LAX (,y)
	0,  // 0xA4: LDY zp
	0,  // 0xA5: LDA zp
	0,  // 0xA6: LDX zp
	fn_lax_zp,  // 0xA7: Undocumented - LAX zp
	0,     // 0xA8: TAY
	0, // 0xA9: LDA imm
	0,     // 0xAA: TAX
	fn_lax,     // 0xAB: Undocumented - LAX
	0, // 0xAC: LDY abs
	0, // 0xAD: LDA abs
	0, // 0xAE: LDX abs
	0, // 0xAF: LAX abs
	0,     // 0xB0: BCS
	0,   // 0xB1: LDA (),y
	0,
	fn_lax_y2,  // 0xB3: LAX (),y
	0,// 0xB4: LDY zp,x
	0,// 0xB5: LDA zp,x
	0,// 0xB6: LDX zp,y
	0,// 0xB7: LAX zp,y
	0,     // 0xB8: CLV
	0,//0xB9: LDA abs,y
	0,     // 0xBA: TSX
	fn_las_abs_y,//0xBB: Undocumented - LAS abs,y
	0,//0xBC: LDY abs,x
	0,//0xBD: LDA abs,x
	0,//0xBE: LDX abs,y
	0,//0xBF: LAX abs,y
	0, // 0xC0: CPY imm
	0,// 0xC1: CMP (,x)
	0, // 0xC2: Undocumented - NOP imm
	fn_dcp_indx,// 0xC3: Undocumented - DCP (,x)
	0,  // 0xC4: CPY zp
	0,  // 0xC5: CMP zp
	0,  // 0xC6: DEC zp
	fn_dcp_zp,  // 0xC7: Undocumented - DCP zp
	0,     // 0xC8: INY
	0, // 0xC9: CMP imm
	0,     // 0xCA: DEX
	fn_sbx_imm, // 0xCB: Undocumented - SBX imm
	0, // 0xCC: CPY abs
	0, // 0xCD: CMP abs
	0, // 0xCE: DEC abs
	fn_dcp_abs, // 0xCF: Undocumented - DCP abs
	0,     // 0xD0: BNE
	0,// 0xD1: CMP (),y
	0,
	fn_dcp_indy,// 0xD3: Undocumented - DCP (),y
	0,// 0xD4: Undocumented - NOP zp,x
	0,// 0xD5: CMP zp,x
	0,// 0xD6: DEC zp,x
	fn_dcp_zp_x,// 0xD7: Undocumented - DCP zp,x
	0,     // 0xD8: CLD
	0,//0xD9: CMP abs,y
	0,     // 0xDA: Undocumented - NOP
	fn_dcp_abs_y,//0xDB: Undocumented - DCP abs,y
	0,//0xDC: Undocumented - NOP abs,x
	0,//0xDD: CMP abs,x
	0,//0xDE: DEC abs,x
	fn_dcp_abs_x,//0xDF: Undocumented - DCP abs,x
	0, // 0xE0: CPX imm
	0,// 0xE1: SBC (,x)
	0, // 0xE2: Undocumented - NOP imm
	fn_isb_indx,// 0xE3: Undocumented - ISB (,x)
	0,  // 0xE4: CPX zp
	0,  // 0xE5: SBC zp
	0,  // 0xE6: INC zp
	fn_isb_zp,  // 0xE7: Undocumented - ISB zp
	0,     // 0xE8: INX
	0, // 0xE9: SBC imm
	0,     // 0xEA: NOP
	0, // 0xEB: Undocumented - SBC imm
	0, // 0xEC: CPX abs
	0, // 0xED: SBC abs
	0, // 0xEE: INC abs
	fn_isb_abs, // 0xEF: Undocumented - ISB abs
	0,     // 0xF0: BEQ
	0,// 0xF1: SBC (),y
	0,
	fn_isb_indy,// 0xF3: Undocumented - ISB (),y
	0, // 0xF4: Undocumented - NOP zpx
	0,// 0xF5: SBC zp,x
	0,// 0xF6: INC zp,x
	fn_isb_zp_x,// 0xF7: Undocumented - ISB zp,x
	0,     // 0xF8: SED
	0,//0xF9: SBC abs,y
	0,     // 0xFA: Undocumented - NOP
	fn_isb_abs_y,//0xFB: Undocumented - ISB abs,y
	0,//0xFC: Undocumented - NOP abs,x
	0,//0xFD: SBC abs,x
	0,//0xFE: INC abs,x
	fn_isb_abs_x,//0xFF: Undocumented - ISB abs,x
};
//*/


char* ops[] = {
	"00c BRK",
    "01: ORA (,x)",
	"02: -?-",
    "03: U SLO (,x)",
    "04: U NOP zp",
    "05: ORA zp",
    "06: ASL zp",
    "07: U SLO zp",
    "08: PHP",
    "09: ORA imm",
    "0A: ASL A",
    "0Bc U ANC imm",
    "0C: U NOP abs",
    "0D: ORA abs",
    "0E: ASL abs",
    "0F: U SLO abs",
    "10: BPL",
    "11: ORA (),y",
	"12: -?-",
    "13: U SLO (),y",
    "14: U NOP zp,x",
    "15: ORA zp,x",
    "16: ASL zp,x",
    "17: U SLO zp,x",
    "18: CLC",
    "19: ORA abs,y",
    "1A: U NOP",
    "1B: U SLO abs,y",
    "1C: U NOP abs,x",
    "1D: ORA abs,x",
    "1E: ASL abs,x",
    "1F: U SLO abs,x",
    "20: JSR",
    "21: AND (,x)",
	"22: -?-",
    "23: U RLA (,x)",
    "24: BIT zp",
    "25: AND zp",
    "26: ROL zp",
    "27: U RLA zp",
    "28: PLP",
    "29: AND",
    "2A: ROL A",
    "2Bc U ANC imm",
    "2C: BIT abs",
    "2D: AND abs",
    "2E: ROL abs",
    "2F: U RLA abs",
    "30: BMI",
    "31: AND (),y",
	"32: -?-",
    "33: U RLA (),y",
    "34: U NOP zp,x",
    "35: AND zp,x",
    "36: ROL zp,x",
    "37: U RLA zp,x",
    "38: SEC",
    "39: AND abs,y",
    "3A: U NOP",
    "3B: U RLA abs,y",
    "3C: U NOP abs,x",
    "3D: AND abs,x",
    "3E: ROL abs,x",
    "3F: U RLA abs,x",
    "40: RTI",
    "41: EOR (,x)",
	"22: -?-",
    "43c U SRE (,x)",
    "44:",
    "45: EOR zp",
    "46: LSR zp",
    "47c U SRE zp",
    "48: PHA",
    "49: EOR imm",
    "4A: LSR A",
    "4Bc U ASR imm",
    "4C: JMP",
    "4D: EOR abs",
    "4E: LSR abs",
    "4Fc U SRE abs",
    "50: BVC",
    "51: EOR (),y",
	"52: -?-",
    "53c U SRE (),y",
    "54:",
    "55: EOR zp,x",
    "56: LSR zp,x",
    "57: U SRE zp,x",
    "58: CLI",
    "59: EOR abs,y",
    "5A: U NOP",
    "5Bc U SRE abs,y",
    "5C: U NOP abs,x",
    "5D: EOR abs,x",
    "5E: LSR abs,x",
    "5Fc U SRE abs,x",
    "60: RTS",
    "61: ADC (,x)",
	"62: -?-",
    "63: U RRA (,x)",
    "64: U NOP zp",
    "65: ADC zp",
    "66: ROR zp",
    "67: U RRA zp",
    "68: PLA",
    "69: ADC imm",
    "6A: ROR A",
    "6Bc U ARR",
    "6C: JMP ()",
    "6D: ADC abs",
    "6E: ROR abs",
    "6F: U RRA abs",
    "70: BVS",
    "71: ADC (),y",
	"72: -?-",
    "73: U RRA (,y)",
    "74: U NOP zp,x",
    "75: ADC zp,x",
    "76: ROR zp,x",
    "77: U RRA zp,x",
    "78: SEI",
    "79: ADC abs,y",
    "7A: U NOP",
    "7B: U RRA abs,y",
	"7C: -?-",
    "7D: ADC abs,x",
    "7E: ROR abs,x",
    "7F: U RRA abs,x",
    "80: U NOP imm",
    "81: STA (,x)",
    "82: U NOP imm",
    "83c U SAX (,x)",
    "84: STY zp",
    "85: STA zp",
    "86: STX zp",
    "87c U SAX zp",
    "88: DEY",
    "89: U NOP imm",
    "8A: TXA",
    "8Bc U ANE",
    "8C: STY abs",
    "8D: STA abs",
    "8E: STX abs",
    "8Fc U SAX abs",
    "90: BCC",
    "91: STA (),y",
	"92: -?-",
    "93c U SHA (),y",
    "94: STY zp,x",
    "95: STA zp,x",
    "96: STX zp,y",
    "97c U SAX zp,y",
    "98: TYA",
    "99: STA abs,y",
    "9A: TXS",
    "9Bc U SHS abs,y",
    "9Cc U SHY abs,x",
    "9D: STA abs,x",
    "9Ec U SHX abs,y",
    "9Fc U SHA abs,y",
    "A0: LDY imm",
    "A1: LDA (,x)",
    "A2: LDX imm",
    "A3c U LAX (,y)",
    "A4: LDY zp",
    "A5: LDA zp",
    "A6: LDX zp",
    "A7c U LAX zp",
    "A8: TAY",
    "A9: LDA imm",
    "AA: TAX",
    "ABc U LAX",
    "AC: LDY abs",
    "AD: LDA abs",
    "AE: LDX abs",
    "AF: LAX abs",
    "B0: BCS",
    "B1: LDA (),y",
	"82: -?-",
    "B3c  LAX (),y",
    "B4: LDY zp,x",
    "B5: LDA zp,x",
    "B6: LDX zp,y",
    "B7: LAX zp,y",
    "B8: CLV",
    "B9: LDA abs,y",
    "BA: TSX",
    "BBc U LAS abs,y",
    "BC: LDY abs,x",
    "BD: LDA abs,x",
    "BE: LDX abs,y",
    "BF: LAX abs,y",
    "C0: CPY imm",
    "C1: CMP (,x)",
    "C2: U NOP imm",
    "C3c U DCP (,x)",
    "C4: CPY zp",
    "C5: CMP zp",
    "C6: DEC zp",
    "C7c U DCP zp",
    "C8: INY",
    "C9: CMP imm",
    "CA: DEX",
    "CBc U SBX imm",
    "CC: CPY abs",
    "CD: CMP abs",
    "CE: DEC abs",
    "CFc U DCP abs",
    "D0: BNE",
    "D1: CMP (),y",
	"D2: -?-",
    "D3c U DCP (),y",
    "D4: U NOP zp,x",
    "D5: CMP zp,x",
    "D6: DEC zp,x",
    "D7c U DCP zp,x",
    "D8: CLD",
    "D9: CMP abs,y",
    "DA: U NOP",
    "DBc U DCP abs,y",
    "DC: U NOP abs,x",
    "DD: CMP abs,x",
    "DE: DEC abs,x",
    "DFc U DCP abs,x",
    "E0: CPX imm",
    "E1: SBC (,x)",
    "E2: U NOP imm",
    "E3c U ISB (,x)",
    "E4: CPX zp",
    "E5: SBC zp",
    "E6: INC zp",
    "E7c U ISB zp",
    "E8: INX",
    "E9: SBC imm",
    "EA: NOP",
    "EB: U SBC imm",
    "EC: CPX abs",
    "ED: SBC abs",
    "EE: INC abs",
    "EFc U ISB abs",
    "F0: BEQ",
    "F1: SBC (),y",
	"F2: -?-",
    "F3c U ISB (),y",
    "F4: U NOP zpx",
    "F5: SBC zp,x",
    "F6: INC zp,x",
    "F7c U ISB zp,x",
    "F8: SED",
    "F9: SBC abs,y",
    "FA: U NOP",
    "FBc U ISB abs,y",
    "FC: U NOP abs,x",
    "FD: SBC abs,x",
    "FE: INC abs,x",
    "FFc U ISB abs,x",
};


uint8_t* save_cpu(uint8_t* p)
{
	*p++ = the_cpu->a;
	*p++ = the_cpu->x;
	*p++ = the_cpu->y;
	*p++ = the_cpu->p;
	*p++ = the_cpu->s;
	*(uint16_t*)p = the_cpu->pc; p+=2;
	*p++ = the_cpu->nmi;
	*p++ = the_cpu->interrupt;
	*(uint32_t*)p = the_cpu->cycles; p+=4;
	return p;
}

uint8_t* load_cpu(uint8_t* p)
{
	the_cpu->a=*p++;
	the_cpu->x=*p++;
	the_cpu->y=*p++;
	the_cpu->p=*p++;
	the_cpu->s=*p++;
	the_cpu->pc = *(uint16_t*)p; p+=2;
	the_cpu->nmi=*p++;
	the_cpu->interrupt=*p++;
	the_cpu->cycles = *(uint32_t*)p; p+=4;
	return p;
}


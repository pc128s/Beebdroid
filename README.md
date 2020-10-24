Beebdroid

Android port by Reuben Scratton of the BeebEm emulator for BBC microcomputers.

Changes

Jan 2020
- [TB] Adapted assembly code to Position Independent Code, as required by new Android security requirements.
- [TB] Added keycaps mode (Ab*) and mouse on ADVAL to support programming.
- [TB] Added snapshot handling to 'save' programming.

Oct 2002
- [TB] Initial 32-bit assembly port to 64-bit assembly, according to new Android application requirements. Sound is broken on x86_64.

Bugs
- games are broken: the machine just resets - might only affect 64 bit architecture, might arise from keyboard changes
- saved machine states may be partly broken, perhaps due to CPU structure entries
- opcodes not used during boot sequence completion are untested and probably broken in 64 emulations
- no automated testing of emulated machine. Maybe http://visual6502.org/wiki/index.php?title=6502TestPrograms
- would be nice to have a full C layer CPU emulation, to avoid needing to port to 64 bit architecture. Perhaps steal it from latest BeebEm.

Overview

The emulator supports all 6502 opcodes, including the unofficial ones arising because of the CPU's internal microcoding. (On the 64bit ports, there may be bugs in these unofficial opcodes.)

At start-up, the emulator launches with an on-screen version of the original BBC micro keyboard, targeting games which need precise keymapping, so symbols on the keyboard may not match what appears on the screen. Pressing the JoyPad icon switches to joypad mode and offers 'Ab*' mode. Ab* mode maps the keyboard symbols to make what is pressed appear on the screen.

The disk icon allows you to download games from the internet archive under 'Games', or use local images 'Local', or 'Saved' to create snapshots of the machine, or restore to a previously saved image.

Description from Google Play Store:

We grew up with the BBC Micro and we loved it, so we're bringing it back to the world!

See that Android phone in your hand? That's not Intel inside - it's powered by an ARM processor. The 'A' in 'ARM' stood for Acorn, a visionary British company that produced the seminal BBC Micro, and thirty years later - coming full circle - your phone hosts its venerable ancestor today.

Have fun reliving classic games from the 1980s, hosted by BBC Micro preservation website stairwaytohell.com - any questions, please email support@littlefluffytoys.mobi

Works at the full 50 frames per second on most recent powerful Android phones and tablets. This means anything significantly better than an HTC Desire or Nexus One (except, annoyingly, the brand new Galaxy Nexus, because it has the archaic GPU from 2010's Galaxy S pushing 240% of the pixels), or any Android 3.x or 4.x tablet. If you try to run this thing on something like a Wildfire or a ZTE Blade, please don't whinge to us that it runs like a dog - just go and buy a Galaxy S II already ;) It's an extremely faithful emulation, way moreso than other machines' emulators, but if you think you can make it quicker, get involved - it's open-source - code it!

Works with the specialised controls of the Sony Ericsson Xperia Play (but doesn't run at 50 frames per second).

This FREE and OPEN SOURCE application - the source code is published on Github at https://github.com/littlefluffytoys/Beebdroid - is based on B-Em for Linux by Tom Walker and licensed under GNU General Public License v2.0 as detailed at http://www.gnu.org/licenses/gpl-2.0.html - we encourage others to assist us in developing Beebdroid further. This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the license for more details.

Beebdroid does not ship with any third party programs such as games - these are accessed via third party websites which have hosted such programs for years. Should any licence holders object to third party websites providing such programs, please contact the third party websites in question. We are furthermore also happy on a best-effort basis to block the visibility of specific programs within Beebdroid upon written request from verified product licence holders. We are also able to offer an in-app payment solution for your programs in such circumstances - please contact us for details.

The rights to the BBC Micro ROMs are believed by the emulator community to have effectively fallen into the public domain, having lain unclaimed for many years - Acorn itself is of course long bankrupt, and the documentation for the rights is rumoured to have gone down in the World Trade Center on 9/11. If you believe that you have a claim to the ROMs that has never been disclosed to the emulator community, or indeed has never been proven under law, remember that this is a labour of love, a free and open source preservation effort, and so please talk to us in the first instance, cheers!

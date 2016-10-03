# OpenJDK9 with extended SCTP Support

[![N|Solid](https://s9.postimg.org/vg1jionun/javasctp.png)]()

This repository contains a modified version of OpenJDK9 adding support of SCTP protocol under OS X by utilizing [sctplab/SCTP_NKE_ElCapitan](https://github.com/sctplab/SCTP_NKE_ElCapitan) project (SCTP implementation of the FreeBSD kernel modified to work within the Mac **OS X** kernel infrastructure as a network kernel extension).  
*Making JVM supports SCTP also in OS X, gave me the idea to try the same approach to try to add Windows SCTP support as well, by using any of the SCTP drivers currently available for this operating system. For now, let's cross our fingers...*

## SCTP Supported Operating Systems by JVM
- **Linux** (native support by all JVMs, no need to use this project)
- **OS X** (tested with El Capitan, working fine)
- **Windows x64** **(not currently supported but planned, i'm trying to see what i can do with any of the available Sctp Drivers for Windows)*

## Why?
I started this project in order to make my life even easier when working with the [RestComm/jss7](https://github.com/RestComm/jss7) stack which utilizes [RestComm/sctp](https://github.com/RestComm/sctp). Since i'm working using **OS X**, it was always difficult to deploy my telco app all the time to a **linux** box in order to test it, since all JVMs natively supports SCTP operations, so, i decided to modify the JVM including native code and NIO java code in order to add the *so-much-wanted* SCTP support for OS X. At first I was not sure that this effort will succeed but finally i was wrong. This JVM is based on JDK9 sources, everything is untouched except the OS X part of SCTP protocol (which till now always threw "Unsupported Platform" Exceptions).

# Just get the JRE binary
If you want to run an application that uses SCTP protocol and you don't want to build the JVM from sources using this repo, you may **[download my build here](http://www117.zippyshare.com/v/pWx9qPss/file.html)**. 

***ATTENTION: This JVM's SCTP requires installation of SCTP NKE. In order to be able to use SCTP protocol under OSX, you should follow the protocol installation guide as shown at [sctplab/SCTP_NKE_ElCapitan](https://github.com/sctplab/SCTP_NKE_ElCapitan)***


# How to Build
> git clone **https://github.com/diamantisk/openjdk9-sctp.git**

> cd **openjdk9-sctp**

> **./configure --disable-warnings-as-errors**

> make **clean images**

***Then, you may find both JRE and JDK builds of this JVM under images subdirectory of the directory you will find under build directory.*** *It would be something like build/macosx-x86_64-normal-server-release/images/**

# License
Inherits **OpenJDK**'s license as seen in LICENSE file. (The GNU General Public License (GPL))

# Credits
My name is **Diamantis Kyriakakis** and you may contact me by using any of the following:
- Email: diamantis.kiriakakis@gmail.com
- Linked-In: https://www.linkedin.com/in/diamantis-kyriakakis-131b9a66




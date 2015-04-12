# The Neurolyzer Streamer



## Installation

Install Java version 7+ on your system. If you are on Mac OS X, it must be
available from scratch. Linux users can to search for Java using standard
package manager for their distribution.

## Usage

    $ java -jar ns-streamer-0.1.0-standalone.jar [command] [options]

#### Commands

* `ports` - print list of available ports to connect and exit
* `neurosky` - connect and process data from NeuroSky headsets

#### Options

* `-p, --port ID` - ID of the EEG headset port to read from
* `-c, --config FILENAME` - set configuration file (JSON), default is
`config.json` in current directory
* `-h, --help` - displays help message

## Examples

Start the streamer, which reads data from NeuroSky headset on port 4 and send
it to the Neurolyzer service.

    $ java -jar ns-streamer.jar neurosky 4

## Contribute

## Thanks

Sam Aaron and Jeff Rose, contributors of `serial-port`, for the library,
which is copied over to this repository.

## License

Copyright © 2015 Efficio s.r.o.

Distributed under the Eclipse Public License either version 1.0 or (at
your option) any later version.

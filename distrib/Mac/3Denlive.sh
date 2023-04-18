DIR="$( cd "$( dirname "$0" )" && pwd )"
cd $DIR
java -XstartOnFirstThread -Xmx4G -cp 3Denlive.jar:swt.jar:MetaDataExtractor.jar:xmpcore-6.0.6.jar:svgSalamander-1.1.2.jar:bsh-2.0b6.jar -Djava.library.path=./lib com.cubaix.TDenlive.TDenlive


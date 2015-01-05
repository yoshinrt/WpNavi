@echo off
set perlscr=%0 %*
set perlscr=%perlscr:\=/%
C:\cygwin\bin\bash --login -i -c 'cd "%CD%";CYGWIN=nodosfilewarning perl -x %perlscr%'
goto :EOF

##############################################################################
#!/usr/bin/perl -w
# .tab=4

$File = 'AndroidManifest.xml';

$Debug = defined( $ARGV[ 0 ] ) && $ARGV[ 0 ] =~ /^d/;

# svn リビジョンを得る
`LANG=C svn info -r HEAD` =~ /Revision:\s+(\d+)/;
$Rev = $1;

# svn 更新されているかを得る
$ModCnt = `svn stat | grep -v '\?' | wc -l`;

# xml 全リード
open( $fp, "< $File" );
$_ = join( '', <$fp> );
close( $fp );

# xml のバージョンコードを得る
/android:versionCode="(\d+)/;
$PrevRev = $1;

# xml のビルド種別を得る
$PrevDebug = /<!--IF_RELEASE--><!--/;

if(
	$ModCnt != 0 ||					# svn 更新された
	!$Debug && $PrevRev != $Rev ||	# リリースビルドかつ rev 番号に差異がある
	$PrevDebug != $Debug			# ビルド種別が違う
){
	++$Rev;
	
	s/(<!--IF_(DEBUG|RELEASE)-->)(?:<!--)?/&OpenTag( $1, $2 )/ge;
	s/(?:-->)?(<!--END_(DEBUG|RELEASE)-->)/&CloseTag( $1, $2 )/ge;
	s/(android:versionCode="|android:versionName="r).+?"/$1$Rev"/g if( !$Debug );
	
	open( $fpOut, "> ${File}_" );
	print( $fpOut $_ );
	close();
	
	unlink( $File );
	rename( "${File}_", $File );
}

sub OpenTag {
	local( $_, $type ) = @_;
	$_ .= '<!--' if( $type ne ( $Debug ? 'DEBUG' : 'RELEASE' ));
	$_;
}

sub CloseTag {
	local( $_, $type ) = @_;
	$_ = "-->$_" if( $type ne ( $Debug ? 'DEBUG' : 'RELEASE' ));
	$_;
}

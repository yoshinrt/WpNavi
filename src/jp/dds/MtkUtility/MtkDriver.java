package jp.dds.MtkUtility;

import java.io.*;
import java.util.Calendar;
import java.util.UUID;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.os.Handler;
import android.os.Message;
import android.util.Log;

public class MtkDriver implements Runnable{

	InputStream		InStream	= null;
	OutputStream	OutStream	= null;

	Thread ReadThread	= null;
	volatile boolean bKillThread	= false;
	String			BTName;

	// Mtk data
	volatile int	iLogSize		= -1;

	byte [] FailSector	= null;
	byte [] LogBuf		= null;
	volatile int		iInterval;
	volatile int		iLogReadSize;

	BluetoothDevice device;
	BluetoothSocket BTSock = null;
	BluetoothAdapter mBluetoothAdapter = null;
	private static final UUID BT_UUID = UUID.fromString( "00001101-0000-1000-8000-00805F9B34FB" );

	static final int SECTOR_SIZE			= 64 * 1024;	// 64KB
	static final int HEADER_SIZE			= 512;
	static final int DYNAMIC_PATTERN_SIZE	= 16;

	static final boolean bDebug = MtkUtilityActivity.bDebug;
	Handler	MsgHandler;

	static final int	OPEN_OK					= 0x0;
	static final int	OPEN_BT_NOT_ENABLED		= 0x1;
	static final int	OPEN_FAILED				= 0x2;
	static final int	GET_LOG_SIZE			= 0x3;
	static final int	GET_FAILED_SECTOR		= 0x4;
	static final int	SET_INTERVAL			= 0x5;
	static final int	GET_INTERVAL			= 0x6;
	static final int	GET_LOG					= 0x7;
	static final int	GET_LOG_PROCEEDING		= 0x8;
	static final int	FORMAT_OK				= 0x9;
	static final int	SET_NMEA_INTERVAL		= 0xA;

	/*** コンストラクタ ************************************************/

	public MtkDriver( Handler handler ){
		InStream	= null;
		OutStream	= null;
		ReadThread	= null;

		iLogSize	= -1;
		bKillThread = false;
		MsgHandler	= handler;

		mBluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
	}

	/*** onen ***********************************************************/

	public int Open( String argBTName ){

		if( bDebug ) Log.d( "MtkUtility", "MtkDriver::Open" );

		BTName	= argBTName;
		( ReadThread = new Thread( this )).start();	// リードスレッド開始
		return 0;
	}

	/*** コマンド送信 ***************************************************/

	int SendCmd( String format, Object ... args ){

		if( OutStream == null ) return -1;

		String Str = String.format( format, args );

		// チェックサム計算
		int Sum = 0;
		for( int i = 0; i < Str.length(); ++i ){
			Sum ^= Str.codePointAt( i );
		}

		Str = String.format( "$%s*%02X\r\n", Str, Sum & 0xFF );
		if( bDebug ) DebugMsg( "<<<%s", Str );

		try{
			byte[] Buf = Str.getBytes( "US-ASCII" );
			OutStream.write( Buf, 0, Buf.length );
		}catch( Exception e ){}

		return 0;
	}

	/*** close **********************************************************/

	int Close(){
		try{
			if( BTSock != null ){
				BTSock.close();
				BTSock = null;
			}
		}catch( IOException e ){}

		KillThread();
		return 0;
	}

	/*** スレッド制御 ***************************************************/

	public void run(){
		final int iBufSize = 8 * 1024;
		byte	Buf[] = new byte[ iBufSize ];
		int		iStart	= 0;
		int		iSize	= 0;
		int		iReadSize;
		int		i;

		//BufferedOutputStream  fsDebugLog = null;
		//try{
		//	fsDebugLog    = new BufferedOutputStream( new FileOutputStream( "/sdcard/z" ));
		//}catch( Exception e ){ DebugMsg( " " + e ); }

		if( bDebug ) Log.d( "MtkUtility", "MtkDriver::run() started" );
		/*** open 処理 ***/
		// If the adapter is null, then Bluetooth is not supported
		if( mBluetoothAdapter == null ){
			//iMessage = R.string.statmsg_bluetooth_not_available;
			MsgHandler.sendEmptyMessage( OPEN_BT_NOT_ENABLED );
			return;
		}

		// BT ON でなければエラーで帰る
		if( bDebug ) Log.d( "MtkUtility", "MtkDriver::Enable" );
		if( !mBluetoothAdapter.isEnabled()){
			//Intent enableIntent = new Intent( BluetoothAdapter.ACTION_REQUEST_ENABLE );
			//startActivityForResult( enableIntent, 1 );
			bKillThread = true;
			MsgHandler.sendEmptyMessage( OPEN_BT_NOT_ENABLED );
			return;
		}

		// BT の MAC アドレスを求める
		device = mBluetoothAdapter.getRemoteDevice( BTName.substring( BTName.length() - 17 ));

		// Get a BluetoothSocket for a connection with the
		// given BluetoothDevice
		if( bDebug ) Log.d( "MtkUtility", "MtkDriver::createRfcommSocket" );
		try{
			BTSock = device.createRfcommSocketToServiceRecord( BT_UUID );
		}catch( IOException e ){
			if( BTSock != null ) try{
				BTSock.close();
				BTSock = null;
			}catch( IOException e1 ){}

			//iMessage = R.string.statmsg_bluetooth_server_error;
			if( bDebug ) Log.d( "MtkUtility", "MtkDriver::createRfcommSocket:Failed" );
			MsgHandler.sendEmptyMessage( OPEN_FAILED );
			return;
		}

		try{
			// ソケットの作成 12秒でタイムアウトらしい
			if( bDebug ) Log.d( "MtkUtility", "MtkDriver::Open:connecting..." );
			BTSock.connect();
			InStream	= BTSock.getInputStream();
			OutStream	= BTSock.getOutputStream();
			if( bDebug ) Log.d( "MtkUtility", "MtkDriver::Open:connected" );
			MsgHandler.sendEmptyMessage( OPEN_OK );

		}catch( Exception e ){
			if( bDebug ) Log.d( "MtkUtility", "MtkDriver::Open:IOException" );
			if( BTSock != null ) try{
				BTSock.close();
				BTSock = null;
			}catch( IOException e1 ){}
			MsgHandler.sendEmptyMessage( OPEN_FAILED );
			return;
		}

		/*** Read ループ ***/
		try{
			while( !bKillThread ){
				// buf の end ptr の続きからデータを読む
				iReadSize = InStream.read( Buf, iSize, iBufSize - iSize );
				//fsDebugLog.write( Buf, iSize, iReadSize );

				if( iReadSize != 0 ){
					iSize += iReadSize;
					iStart = 0;
					//if( bDebug ) Log.d( "MtkUtility", "BufUsage:" + iSize );

					for(;;){
						// \n スキップ
						if( iStart < iSize && Buf[ iStart ] == '\n' ) ++iStart;

						// \r を探す
						for( i = iStart; i < iSize; ++i ){
							if( Buf[ i ] == '\r' ) break;
						}

						if( i < iSize ){
							// \r が見つかったのでコマンド解析
							// -3 は chksum の分
							ParseCmd( Buf, iStart, i - 3 );
							iStart = i + 1;
						}else{
							// \r が見つからなかったので，
							// iStart を buf の先頭に移動して解析を終わる
							if( iStart != 0 ){
								for( i = iStart; i < iSize; ++i ){
									Buf[ i - iStart ] = Buf[ i ];
								}
								iSize -= iStart;
							}
							break;
						}
					}
				}else{
					Thread.sleep( 100 );
				}
			}
		}catch( Exception e ){
			if( bDebug ) Log.d( "MtkUtility", "MtkDriver::run() exit" + e );
		}

		if( bDebug ) Log.d( "MtkUtility", "MtkDriver::run() exit" );
		//try { fsDebugLog.close(); } catch (IOException e) {}

		bKillThread = false;
	}

	public void KillThread(){
		bKillThread = true;
		try{
			while( bKillThread && ReadThread != null && ReadThread.isAlive()){
				ReadThread.interrupt();
				Thread.sleep( 100 );
			}
		}catch( InterruptedException e ){}
	}

	/*** MTK コマンド ***************************************************/

	// レコードサイズ取得
	void GetRecordSize(){
		if( OutStream == null ) return;
		SendCmd( "PMTK182,2,8" );	// RCD ADDR
	}

	// Failed sector 取得
	void GetFailedSector(){
		if( OutStream == null ) return;
		SendCmd( "PMTK182,2,11" );	// RCF FSECTOR
	}

	// interval 設定
	void SetInterval( int ms ){
		if( OutStream == null ) return;
		SendCmd( "PMTK300,%d,0,0,0,0", ms );
	}

	// interval 取得
	void GetInterval(){
		if( OutStream == null ) return;
		SendCmd( "PMTK400" );
	}

	// ログ取得
	void GetLog(){
		if( OutStream == null ) return;
		LogBuf = new byte[ iLogSize ];
		iLogReadSize = 0;
		SendCmd( "PMTK182,7,0,%X", iLogSize );	// READ LOG
	}

	// NMEA 頻度設定
	void SetNMEAInterval( int iRMC, int iGGA, int iGSV ){
		SendCmd( "PMTK314,0,%d,0,%d,0,%d,0,0,0,0,0,0,0,0,0,0,0,0,0", iRMC, iGGA, iGSV );
	}

	void Format(){
		if( OutStream == null ) return;
		SendCmd( "PMTK182,5" );		// stop logging
		SendCmd( "PMTK182,6,1" );	// format all
	}

	// バイナリログセーブ
	int SaveBinLog( String Dir ){
		if( bDebug ) DebugMsg( "SaveBinLog\n" );

		if( OutStream == null ) return -1;
		OutputStream	fsBinLog	= null;

		// 日付
		Calendar Date = Calendar.getInstance();
		String s = String.format(
			"%s/%04d%02d%02d_%02d%02d%02d.bin",
			Dir,
			Date.get( Calendar.YEAR ),
			Date.get( Calendar.MONTH ) + 1,
			Date.get( Calendar.DAY_OF_MONTH ),
			Date.get( Calendar.HOUR_OF_DAY ),
			Date.get( Calendar.MINUTE ),
			Date.get( Calendar.SECOND )
		);

		// ログファイルオープン
		try{
			fsBinLog = new FileOutputStream( s );
		}catch( Exception e ){
			return -1;
		}

		try{
			fsBinLog.write( LogBuf, 0, iLogSize );
			fsBinLog.close();
		}catch( Exception e ){}
		return 0;
	}

	int iParseStart;
	int iParseEnd;

	void ParseCmd( byte [] Buf, int iStart, int iEnd ){
		Message Msg = null;
		int iNum1;

		if( Buf[ iStart ] != '$' ){
			if( bDebug ) DebugMsg( "Wrong output? %02X [%s]", Buf[ iStart ], new String( Buf, iStart, iEnd - iStart ));
		}

		// $PMTK 以外は無視
		//if( "$PMTK".equals( new String( Buf, iStart, 5 ))){
		if(
			Buf[ iStart + 0 ] == '$' &&
			Buf[ iStart + 1 ] == 'P' &&
			Buf[ iStart + 2 ] == 'M' &&
			Buf[ iStart + 3 ] == 'T' &&
			Buf[ iStart + 4 ] == 'K'
		){
			if( bDebug ) DebugMsg( ">>>[%s]\n", new String( Buf, iStart, iEnd - iStart ));

			iParseStart = iStart += 5;
			iParseEnd	= iEnd;

			int iCmd = ( ParseHex( Buf ) << 16 );
			if( iCmd == 0x01820000 || iCmd == 0x10000 ) iCmd |= ParseHex( Buf );

			if( bDebug ) DebugMsg( "%08X\n", iCmd );

			switch( iCmd ){
			  case 0x00010182:
				if(( iNum1 = ParseHex( Buf )) == 0x7 ){
					// read log completed
					iLogReadSize = iLogSize;
				}else if( iNum1 == 6 ){
					// format completed
					MsgHandler.sendEmptyMessage( FORMAT_OK );
				}
				break;

			  case 0x00010300:	// return set interval
				MsgHandler.sendEmptyMessage( SET_INTERVAL );
				break;

			  case 0x05000000:	// return interval
				Msg = new Message();
				Msg.what	= GET_INTERVAL;
				Msg.arg1	= ParseDec( Buf );
				MsgHandler.sendMessage( Msg );
				break;

			  case 0x01820003:	// return log status
				switch( ParseHex( Buf )){
				  case 0x8:	// RCD ADDR
					iLogSize = ParseHex( Buf );
					if( bDebug ) DebugMsg( "RCD ADDR: %X\n", iLogSize );
					Msg = new Message();
					Msg.what	= GET_LOG_SIZE;
					Msg.arg1	= iLogSize;
					MsgHandler.sendMessage( Msg );
					break;

				  case 0x11:	// fail sector data
					if( bDebug ) DebugMsg( "get fail sector data\n" );
					FailSector = new byte[ 32 ];
					ParseBytes( Buf, FailSector, 0, 32 );
					MsgHandler.sendEmptyMessage( GET_FAILED_SECTOR );
					break;
				}
				break;

			  case 0x01820008:	// log data output
				iLogReadSize = ParseHex( Buf );
				if( bDebug ) DebugMsg( "get log data: %X\n", iLogReadSize );
				ParseBytes( Buf, LogBuf, iLogReadSize, iLogSize - iLogReadSize );
			}
		}
	}

	int ParseHex( byte [] Buf ){
		int		iRet = 0;
		byte	b;
		for( ; iParseStart < iParseEnd; ++iParseStart ){
			b = Buf[ iParseStart ];
			if( '0' <= b && b <= '9' ){
				iRet = ( iRet << 4 ) + b - '0';
			}else if( 'A' <= b && b <= 'F' ){
				iRet = ( iRet << 4 ) + b - 'A' + 10;
			}else if( 'a' <= b && b <= 'f' ){
				iRet = ( iRet << 4 ) + b - 'a' + 10;
			}else{
				break;
			}
		}

		// , までスキップ
		SkipToComma( Buf );
		return iRet;
	}

	int ParseDec( byte [] Buf ){
		int		iRet = 0;
		byte	b;
		for( ; iParseStart < iParseEnd; ++iParseStart ){
			b = Buf[ iParseStart ];
			if( '0' <= b && b <= '9' ){
				iRet = ( iRet * 10 ) + b - '0';
			}else{
				break;
			}
		}

		// , までスキップ
		SkipToComma( Buf );
		return iRet;
	}

	void ParseBytes( byte [] Buf, byte [] OutBuf, int iStart, int iLen ){
		byte	b;
		int		iRet;

		if((( iParseEnd - iParseStart ) & 1 ) != 0 ){
			--iParseEnd;
		}

		while( iParseStart < iParseEnd && iStart < ( iStart + iLen )){

			b = Buf[ iParseStart++ ];
			if( '0' <= b && b <= '9' ){
				iRet = b - '0';
			}else if( 'A' <= b && b <= 'F' ){
				iRet = b - 'A' + 10;
			}else if( 'a' <= b && b <= 'f' ){
				iRet = b - 'a' + 10;
			}else{
				break;
			}
			iRet <<= 4;

			b = Buf[ iParseStart++ ];
			if( '0' <= b && b <= '9' ){
				iRet |= b - '0';
			}else if( 'A' <= b && b <= 'F' ){
				iRet |= b - 'A' + 10;
			}else if( 'a' <= b && b <= 'f' ){
				iRet |= b - 'a' + 10;
			}else{
				break;
			}

			OutBuf[ iStart++ ] = ( byte )iRet;
		}
	}

	void SkipToComma( byte [] Buf ){
		for( ; iParseStart < iParseEnd; ++iParseStart ){
			if( Buf[ iParseStart ] == ',' ){
				++iParseStart;
				break;
			}
		}
	}
	/*** NMEA セーブ ****************************************************/

	static final int FMT_UTC		= ( 1 << 0 );
	static final int FMT_VALID		= ( 1 << 1 );
	static final int FMT_LATITUDE	= ( 1 << 2 );
	static final int FMT_LONGITUDE	= ( 1 << 3 );
	static final int FMT_HEIGHT		= ( 1 << 4 );
	static final int FMT_SPEED		= ( 1 << 5 );
	static final int FMT_TRACK		= ( 1 << 6 );
	static final int FMT_DSTA		= ( 1 << 7 );
	static final int FMT_DAGE		= ( 1 << 8 );
	static final int FMT_PDOP		= ( 1 << 9 );
	static final int FMT_HDOP		= ( 1 << 10 );
	static final int FMT_VDOP		= ( 1 << 11 );
	static final int FMT_NSAT		= ( 1 << 12 );
	static final int FMT_SID		= ( 1 << 13 );
	static final int FMT_ELE		= ( 1 << 14 );
	static final int FMT_AZI		= ( 1 << 15 );
	static final int FMT_SNR		= ( 1 << 16 );
	static final int FMT_RCR		= ( 1 << 17 );
	static final int FMT_MS			= ( 1 << 18 );

	static final int SIZE_UTC		= 4;
	static final int SIZE_VALID		= 2;
	static final int SIZE_LATITUDE	= 8;
	static final int SIZE_LONGITUDE	= 8;
	static final int SIZE_HEIGHT	= 4;
	static final int SIZE_SPEED		= 4;
	static final int SIZE_TRACK		= 4;
	static final int SIZE_DSTA		= 2;
	static final int SIZE_DAGE		= 4;
	static final int SIZE_PDOP		= 2;
	static final int SIZE_HDOP		= 2;
	static final int SIZE_VDOP		= 2;
	static final int SIZE_NSAT		= 2;
	static final int SIZE_SID		= 4;
	static final int SIZE_ELE		= 2;
	static final int SIZE_AZI		= 2;
	static final int SIZE_SNR		= 2;
	static final int SIZE_RCR		= 2;
	static final int SIZE_MS		= 2;

	void SaveNMEA( final String Dir ){
		GetLog();	// Log ロードコマンド

		// Log セーブスレッド起動
		new Thread(
			new Runnable() {
				@Override
				public void run(){
					SaveNMEASub( Dir );
				}
			}
		).start();
	}

	int SaveNMEASub( String Dir ){
		if( bDebug ) DebugMsg( "SaveNMEALog:" + Dir + "\n" );

		if( OutStream == null ) return -1;

		BufferedWriter	fsLog = null;
		int	iFormatReg;
		int	iRecordSize = 0;
		int i;

		Calendar	Date	= Calendar.getInstance();
		double		d	= 0;

		String	StrDate, StrTime, StrLong, StrLati, StrHeight, StrSpeed, StrBearing;

		// 日付
		String s = String.format(
			"%s/%04d%02d%02d_%02d%02d%02d.nmea",
			Dir,
			Date.get( Calendar.YEAR ),
			Date.get( Calendar.MONTH ) + 1,
			Date.get( Calendar.DAY_OF_MONTH ),
			Date.get( Calendar.HOUR_OF_DAY ),
			Date.get( Calendar.MINUTE ),
			Date.get( Calendar.SECOND )
		);

		// ログファイルオープン
		try{
			fsLog    = new BufferedWriter( new FileWriter( s ));
		}catch( Exception e ){
			return -1;
		}

		try{
			int iPtr = 0;
			int iPtrPrev = -1;

			while( iPtr < iLogSize ){
				/*** セクタ先頭の解析 ***/

				// Failed Sector で無いことを確認
				if(
					(
						FailSector[ iPtr / SECTOR_SIZE / 8 ] &
						( 1 << (( iPtr / SECTOR_SIZE ) & 0x7 ))
					) == 0
				){
					if( bDebug ) DebugMsg( "SaveNMEA:inalid Sector: %d\n", iPtr / SECTOR_SIZE );
					iPtr += SECTOR_SIZE;
					continue;
				}

				// 少なくともセクタヘッダの分残りサイズがあるか確認
				if( iLogSize - iPtr < HEADER_SIZE ) break;

				// 少なくともセクタヘッダの分読んだか確認
				while( iLogReadSize - iPtr < HEADER_SIZE ) try{
					Thread.sleep( 100 );
				}catch( Exception e ){};

				iFormatReg = GetI4( LogBuf, iPtr + 0x2 );
				iRecordSize = GetRecordSize( iFormatReg );
				if( bDebug ) DebugMsg( "SaveNMEA:Valid Sector %d: Fmt = %X, Size = %d\n", iPtr / SECTOR_SIZE, iFormatReg, iRecordSize );
				iPtr += HEADER_SIZE;

				/*** ログデータの解析 ***/
				int iSectorEnd = iPtr + SECTOR_SIZE - HEADER_SIZE;
				if( iSectorEnd > iLogSize ) iSectorEnd = iLogSize;

				while( iPtr < iSectorEnd ){
					if(( iPtrPrev & ~0x3FF ) != ( iPtr & ~0x3FF )){
						iPtrPrev = iPtr;
						Message Msg = new Message();
						Msg.what	= GET_LOG_PROCEEDING;
						Msg.arg1	= iPtrPrev;
						MsgHandler.sendMessage( Msg );
					}

					// dynamic setting pattern の検出
					if( iSectorEnd - iPtr >= DYNAMIC_PATTERN_SIZE ){

						while( iLogReadSize - iPtr < DYNAMIC_PATTERN_SIZE ) try{
							Thread.sleep( 100 );
						}catch( Exception e ){};

						// 0xAA x 7 か?
						for( i = 0; i < 7; ++i ){
							if( LogBuf[ iPtr + i ] != ( byte )0xAA ) break;
						}

						if( i >= 7 ){
							if( bDebug ) DebugMsg( "SaveNMEA: Dynamic pattern found: %X ID = %X\n", iPtr, LogBuf[ iPtr + 7 ] );

							if( LogBuf[ iPtr + 7 ] == 2 ){
								iFormatReg = GetI4( LogBuf, iPtr + 8 );
								iRecordSize = GetRecordSize( iFormatReg );
								if( bDebug ) DebugMsg( "SaveNMEA: FormatReg changed: %X\n", iFormatReg );
							}
							iPtr += DYNAMIC_PATTERN_SIZE;
							continue;
						}
					}

					// 通常レコード分のサイズがあるか検出
					if( iSectorEnd - iPtr < iRecordSize ){
						iPtr = ( iPtr + SECTOR_SIZE - 1 ) & ~( SECTOR_SIZE - 1 );
						if( bDebug ) DebugMsg( "SaveNMEA: Sector end detected, next = %X\n", iPtr );
						break;
					}

					// 通常レコード分のサイズを読んだか検出
					while( iLogReadSize - iPtr < iRecordSize ) try{
						Thread.sleep( 100 );
					}catch( Exception e ){};

					// 通常レコードの検出
					StrDate = StrTime = StrSpeed = StrBearing = ",";
					StrLong = StrLati = StrHeight = ",,";

					if(( iFormatReg & FMT_UTC ) != 0 ){
						Date.clear();
						Date.set( 1970, 0, 1 );
						Date.add( Calendar.SECOND, GetI4( LogBuf, iPtr ));

						iPtr += SIZE_UTC;
					}
					if(( iFormatReg & FMT_VALID ) != 0 ){
						iPtr += SIZE_VALID;
					}
					if(( iFormatReg & FMT_LATITUDE ) != 0 ){
						d = GetR8( LogBuf, iPtr );
						if( d >= 0 ){
							StrLati = FormatDeg( d ) + ",N,";
						}else{
							StrLati = FormatDeg( -d ) + ",S,";
						}
						iPtr += SIZE_LATITUDE;
					}
					if(( iFormatReg & FMT_LONGITUDE ) != 0 ){
						d = GetR8( LogBuf, iPtr );
						if( d >= 0 ){
							StrLong = FormatDeg( d ) + ",E,";
						}else{
							StrLong = FormatDeg( -d ) + ",W,";
						}
						iPtr += SIZE_LONGITUDE;
					}
					if(( iFormatReg & FMT_HEIGHT ) != 0 ){
						StrHeight = String.format( "%.03f,M,", GetR4( LogBuf, iPtr ));
						iPtr += SIZE_HEIGHT;
					}
					if(( iFormatReg & FMT_SPEED ) != 0 ){
						StrSpeed = String.format( "%.03f,", GetR4( LogBuf, iPtr ) / 1.85200 );
						iPtr += SIZE_SPEED;
					}
					if(( iFormatReg & FMT_TRACK ) != 0 ){
						StrBearing = String.format( "%.02f,", GetR4( LogBuf, iPtr ));
						iPtr += SIZE_TRACK;
					}
					if(( iFormatReg & FMT_DSTA ) != 0 ){	iPtr += SIZE_DSTA;	}
					if(( iFormatReg & FMT_DAGE ) != 0 ){	iPtr += SIZE_DAGE;	}
					if(( iFormatReg & FMT_PDOP ) != 0 ){	iPtr += SIZE_PDOP;	}
					if(( iFormatReg & FMT_HDOP ) != 0 ){	iPtr += SIZE_HDOP;	}
					if(( iFormatReg & FMT_VDOP ) != 0 ){	iPtr += SIZE_VDOP;	}
					if(( iFormatReg & FMT_NSAT ) != 0 ){	iPtr += SIZE_NSAT;	}
					if(( iFormatReg & FMT_SID ) != 0 ){		iPtr += SIZE_SID;	}
					if(( iFormatReg & FMT_ELE ) != 0 ){		iPtr += SIZE_ELE;	}
					if(( iFormatReg & FMT_AZI ) != 0 ){		iPtr += SIZE_AZI;	}
					if(( iFormatReg & FMT_SNR ) != 0 ){		iPtr += SIZE_SNR;	}
					if(( iFormatReg & FMT_RCR ) != 0 ){		iPtr += SIZE_RCR;	}
					if(( iFormatReg & FMT_MS ) != 0 ){
						Date.add( Calendar.MILLISECOND, GetU2( LogBuf, iPtr ));
						iPtr += SIZE_MS;
					}
					iPtr += 2; // chksum

					// NMEA フォーマット生成
					if(( iFormatReg & FMT_UTC ) != 0 ){
						// 日付生成しなおし
						StrDate = String.format( "%02d%02d%02d,",
							Date.get( Calendar.DAY_OF_MONTH ),
							Date.get( Calendar.MONTH ) + 1,
							Date.get( Calendar.YEAR ) % 100
						);

						StrTime = String.format( "%02d%02d%02d.%03d,",
							Date.get( Calendar.HOUR_OF_DAY ),
							Date.get( Calendar.MINUTE ),
							Date.get( Calendar.SECOND ),
							Date.get( Calendar.MILLISECOND )
						);
					}

					WriteNMEA( fsLog,
						"$GPGGA,%s%s%s1,,,%s,,,",
						StrTime, StrLati, StrLong, StrHeight
					);
					WriteNMEA( fsLog,
						"$GPRMC,%sA,%s%s%s%s%s,,A",
						StrTime, StrLati, StrLong, StrSpeed, StrBearing, StrDate
					);
				}
			}
		}catch( Exception e ){
			if( bDebug ) DebugMsg( "" + e );
		};

		try{
			fsLog.close();
		}catch( Exception e ){
			return -1;
		}

		MsgHandler.sendEmptyMessage( GET_LOG );
		return 0;
	}

	int GetI4( byte [] Buf, int iPtr ){
		return	(  Buf[ iPtr ]     & 0xFF )|
				(( Buf[ iPtr + 1 ] & 0xFF ) <<  8 ) |
				(( Buf[ iPtr + 2 ] & 0xFF ) << 16 ) |
				(( Buf[ iPtr + 3 ]        ) << 24 );
	}

	int GetU2( byte [] Buf, int iPtr ){
		return	(  Buf[ iPtr ]     & 0xFF )|
				(( Buf[ iPtr + 1 ] & 0xFF ) <<  8 );
	}

	double GetR8( byte [] Buf, int iPtr ){
		return Double.longBitsToDouble(
			( long )( GetI4( Buf, iPtr )) & 0xFFFFFFFFL |
			(( long )( GetI4( Buf, iPtr + 4 )) << 32 )
		);
	}

	float GetR4( byte [] Buf, int iPtr ){
		return Float.intBitsToFloat( GetI4( Buf, iPtr ));
	}

	int GetRecordSize( int iReg ){
		int	iRet = 2;	// 2 は chksum

		if(( iReg & FMT_UTC			) != 0 ) iRet += SIZE_UTC;
		if(( iReg & FMT_VALID		) != 0 ) iRet += SIZE_VALID;
		if(( iReg & FMT_LATITUDE	) != 0 ) iRet += SIZE_LATITUDE;
		if(( iReg & FMT_LONGITUDE	) != 0 ) iRet += SIZE_LONGITUDE;
		if(( iReg & FMT_HEIGHT		) != 0 ) iRet += SIZE_HEIGHT;
		if(( iReg & FMT_SPEED		) != 0 ) iRet += SIZE_SPEED;
		if(( iReg & FMT_TRACK		) != 0 ) iRet += SIZE_TRACK;
		if(( iReg & FMT_DSTA		) != 0 ) iRet += SIZE_DSTA;
		if(( iReg & FMT_DAGE		) != 0 ) iRet += SIZE_DAGE;
		if(( iReg & FMT_PDOP		) != 0 ) iRet += SIZE_PDOP;
		if(( iReg & FMT_HDOP		) != 0 ) iRet += SIZE_HDOP;
		if(( iReg & FMT_VDOP		) != 0 ) iRet += SIZE_VDOP;
		if(( iReg & FMT_NSAT		) != 0 ) iRet += SIZE_NSAT;
		if(( iReg & FMT_SID			) != 0 ) iRet += SIZE_SID;
		if(( iReg & FMT_ELE			) != 0 ) iRet += SIZE_ELE;
		if(( iReg & FMT_AZI			) != 0 ) iRet += SIZE_AZI;
		if(( iReg & FMT_SNR			) != 0 ) iRet += SIZE_SNR;
		if(( iReg & FMT_RCR			) != 0 ) iRet += SIZE_RCR;
		if(( iReg & FMT_MS			) != 0 ) iRet += SIZE_MS;

		return iRet;
	}

	String FormatDeg( double dDeg ){
		int iDeg = ( int )dDeg;
		dDeg *= 60;

		return String.format( "%d%09.06f",
			iDeg,
			dDeg - ( iDeg * 60 )
		);
	}

	int WriteNMEA( BufferedWriter fsLog, String format, Object ... args ){
		String s = String.format( format, args );
		int iSum = 0;

		for( int i = 1; i < s.length(); ++i ){
			iSum ^= s.codePointAt( i );
		}

		try{
			fsLog.write( s );
			fsLog.write( String.format( "*%02X\n", iSum & 0xFF ));
		}catch( Exception e ){
			return -1;
		}
		return 0;
	}

	/********************************************************************/

	void DebugMsg( String format, Object ... args ){
		Log.d( "MtkDriver", String.format( format, args ));
	}
}

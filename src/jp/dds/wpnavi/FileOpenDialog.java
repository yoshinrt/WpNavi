// 参考: http://junkcode.aakaka.com/archives/675

package jp.dds.wpnavi;

import java.io.File;
import java.io.FileFilter;
import java.util.Arrays;
import java.util.Comparator;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.util.Log;

/**
 * 選択されたときに呼び出されるリスナー
 *
 */
interface FileOpenDialogListener{
	void onFileSelected( final File file );
}

/**
 * ファイルが選択されたダイアログ
 *
 */
class FileOpenDialog implements DialogInterface.OnClickListener{

	static final boolean bDebug = BuildConfig.DEBUG;;
	
	static final int	MODE_FILE	= 1;
	static final int	MODE_DIR	= 2;
	static final int	MODE_BOTH	= MODE_FILE | MODE_DIR;
	
	private Context mParent = null;			// 親のコンテキスト
	private int 	mSelectedItemIndex	= -1;	// 選択中のアイテムインデックス
	private File[] mFileList;					// 表示中のファイルのリスト
	private int	mMode;						// モード

	private String mCurrDirectory		= null;	// 今居るディレクトリ
	private FileOpenDialogListener mListener;	// リスナー

	private File mLastSelectedItem;				// 最後に選択されたモノ
	private FileFilter	mFilter;

	/**
	 * コンストラクタ
	 * @param parent 親のコンテキスト
	 * @param listener 選択が決まったときに呼び出される
	 * @param openDirectory true:ディレクトリを開く
	 */
	public FileOpenDialog( final Context parent, final FileOpenDialogListener listener, int iMode, FileFilter filter ){
		super();
		mParent			= parent;			// コンテキスト
		mListener		= listener;			// リスナー
		mMode			= iMode;			// モード
		if( filter != null ){
			mFilter	= filter;
		}else{
			// デフォルトは，拡張子なしフィルタ
			mFilter = new FileFilter(){
				public boolean accept( File pathname ){
					// デフォルトのフィルタ:
					// 1. 隠しファイルは除外
					// 2. ファイル選択モードでなければ，dir のみ許可
					return !pathname.getName().startsWith( "." ) &&
						(( mMode & MODE_FILE ) != 0 || pathname.isDirectory());
				}
			};
		}
	}

	/**
	 * ダイアログが選択されたときに呼び出される
	 */
	public void onClick( DialogInterface dialog, int which ){

		// 今の選択されているモノ
		mSelectedItemIndex = which;

		int selectedItemIndex = mSelectedItemIndex;	// 選択されている項目

		// このディレクトリが選択された?
		if(( mMode & MODE_DIR ) != 0 ){
			if( selectedItemIndex == 0 ){
				mListener.onFileSelected( new File( mCurrDirectory ));
				return;
			}
			--selectedItemIndex;
		}
		
		// 上の階層がある場合
		if( !mCurrDirectory.equals( "/" )){
			if( selectedItemIndex == 0 ){
				// 一つ上の階層へ移動する
				openDirectory(( new File( mCurrDirectory )).getParent());
				return;
			}
			--selectedItemIndex;
		}

		// ファイルを取り出す
		mLastSelectedItem = mFileList[ selectedItemIndex ];

		// ディレクトリの場合はそのディレクトリのモノを表示する
		if( mLastSelectedItem.isDirectory()){
			// 次の階層で新しくダイアログを開く
			openDirectory( mLastSelectedItem.getAbsolutePath());

		// ファイルだった場合は、そのファイルを選択されたファイルとして登録する
		}else{
			// ファイルが選択されたことを通知する
			mListener.onFileSelected( mLastSelectedItem );
		}
	}

	/**
	 * 指定のディレクトリを開く
	 * @param dir 開きたいディレクトリ( このディレクトリがルートディレクトリになる )
	 */
	public void openDirectory( String dir ){
		try{
			// dir がファイル名だった場合，その parent を開く
			File file = new File( dir );
			if( file.isFile()) dir = file.getParent();

			// 指定のディレクトリのファイルを全部取り出す
			mFileList = new File( dir ).listFiles( mFilter );
			
			// ソート
			if( mFileList != null ) Arrays.sort( mFileList, new Comparator<File>(){
				public int compare( File f1, File f2 ){
					int iDir1 = f1.isDirectory() ? 1 : 0;
					int iDir2 = f1.isDirectory() ? 1 : 0;
					
					// ファイル or DIR 属性が双方で異なれば，dir 優先の値を返す．
					if( iDir1 != iDir2 ) return iDir1 - iDir2;
					
					return f1.getName().compareToIgnoreCase( f2.getName());
				}
			});
			
			// 今の階層を取っておく
			mCurrDirectory = dir;

			// Alertダイアログのために配列を用意する
			String[] fileNameList = null;
			int itemCount	= 0;
			
			int iArySize	= mFileList == null ? 0 : mFileList.length;
			if(( mMode & MODE_DIR ) != 0 ) ++iArySize;
			if( !dir.equals( "/" )) ++iArySize;
			fileNameList = new String[ iArySize ];
			
			// この Dir を選択 を追加
			if(( mMode & MODE_DIR ) != 0 ){
				fileNameList[ itemCount++ ] = ( String )mParent.getResources().getText( R.string.text_SelectDir );
			}

			// ルートディレクトリ以外
			if( mFileList == null || !dir.equals( "/" )){
				// 上の階層へ行くための項目を追加する
				fileNameList[ itemCount++ ] = ( String )mParent.getResources().getText( R.string.text_ParentDir );
			}
			
			// 見つかったファイルの分だけ追加する
			if( mFileList != null ) for( File currFile : mFileList ){
				// ディレクトリだった
				if( currFile.isDirectory()){
					// 最後に/を加えてディレクトリの表示を
					fileNameList[ itemCount ] = currFile.getName() + "/";
				// ファイルだった
				}else{
					fileNameList[ itemCount ] = currFile.getName();
				}
				itemCount++;
			}

			// ダイアログを表示する
			new AlertDialog.Builder( mParent )
				.setTitle( dir )
				.setItems( fileNameList, this )
				.show();

		}catch( SecurityException se ){
			if( bDebug ) Log.e( "SecurityException", se.getMessage());
		}catch( Exception e ){
			if( bDebug ) Log.e( "Exception", e.getMessage());
		}
	}
}

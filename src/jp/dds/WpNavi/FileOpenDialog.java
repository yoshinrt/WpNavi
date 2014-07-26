// 参考: http://junkcode.aakaka.com/archives/675

package jp.dds.WpNavi;

import java.io.File;
import java.io.FileFilter;
import java.util.Stack;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.util.Log;

/**
 * 選択されたときに呼び出されるリスナー
 *
 */
interface FileOpenDialogListener {
	void onFileSelected(final File file);
}

/**
 * ファイルが選択されたダイアログ
 *
 */
class FileOpenDialog implements DialogInterface.OnClickListener {

	static final boolean bDebug = true;

	private Context mParent = null;				// 親のコンテキスト
	private int mSelectedItemIndex		= -1;	// 選択中のアイテムインデックス
	private File[] mFileList;					// 表示中のファイルのリスト

	private String mCurrDirectory		= null;	// 今居るディレクトリ
	private FileOpenDialogListener mListener;	// リスナー

	private boolean mOpenDirectory;				// ディレクトリを開く
	private File mLastSelectedItem;				// 最後に選択されたモノ

	/**
	 * コンストラクタ
	 * @param parent 親のコンテキスト
	 * @param listener 選択が決まったときに呼び出される
	 * @param openDirectory true:ディレクトリを開く
	 */
	public FileOpenDialog(final Context parent, final FileOpenDialogListener listener, boolean openDirectory) {
		super();
		mParent			= parent;			// コンテキスト
		mListener		= listener;			// リスナー
		mOpenDirectory	= openDirectory;	// ディレクトリだけを開くか
	}

	/**
	 * ダイアログが選択されたときに呼び出される
	 */
	public void onClick(DialogInterface dialog, int which) {

		// 今の選択されているモノ
		mSelectedItemIndex = which;

		int selectedItemIndex = mSelectedItemIndex;	// 選択されている項目

		// 上の階層がある場合
		if( !mCurrDirectory.equals( "/" )){
			// 上の階層ボタン分減らす
			selectedItemIndex--;
		}

		// 上の階層へが選択されてた
		if (selectedItemIndex < 0) {
			// 一つ上の階層へ移動する
			openDirectory(( new File( mCurrDirectory )).getParent());
		} else {
			// ファイルを取り出す
			mLastSelectedItem = mFileList[selectedItemIndex];

			// ディレクトリの場合はそのディレクトリのモノを表示する
			if (mLastSelectedItem.isDirectory()) {
				// 次の階層で新しくダイアログを開く
				openDirectory(mLastSelectedItem.getAbsolutePath());

			// ファイルだった場合は、そのファイルを選択されたファイルとして登録する
			} else {
				// ファイルが選択されたことを通知する
				mListener.onFileSelected(mLastSelectedItem);
			}
		}
	}

	/**
	 * 指定のディレクトリを開く
	 * @param dir 開きたいディレクトリ(このディレクトリがルートディレクトリになる)
	 */
	public void openDirectory(String dir) {
		try {
			// dir がファイル名だった場合，その parent を開く
			File file = new File( dir );
			if( file.isFile()) dir = file.getParent();

			// ディレクトリだけ取り出したい
			/*if (mOpenDirectory == true) {
				// ディレクトリだけ取り出す(フィルタ使う)
				mFileList = new File(dir).listFiles(new FileFilter() {
					public boolean accept(File pathname) {
						// ディレクトリだけ許可
						if (pathname.isDirectory())
							return true;
						return false;
					}
				});
			} else */{
				// 指定のディレクトリのファイルを全部取り出す
				mFileList = new File(dir).listFiles( new FileFilter(){
					public boolean accept( File pathname ){
						// ディレクトリだけ許可
						return !pathname.getName().startsWith( "." ) && (
							pathname.isDirectory() ||
							pathname.getName().endsWith( ".kml" )
						);
					}
				});
			}

			// 今の階層を取っておく
			mCurrDirectory = dir;

			// 何も残ってない(ディレクトリが確定)
			/*
			if (mFileList.length <= 0) {
				mListener.onFileSelected(mLastSelectedItem);
				return ;
			}
			*/

			// Alertダイアログのために配列を用意する
			String[] fileNameList = null;
			int itemCount = 0;

			// ルートディレクトリ以外
			if ( mFileList == null || !dir.equals( "/" )) {
				// 上の階層へ行くための項目を追加する
				fileNameList = new String[ mFileList != null ? mFileList.length + 1 : 1 ];
				fileNameList[ 0 ] = "../";
				itemCount = 1;
			// ルートディレクトリ
			} else {
				// ファイルの数だけ
				fileNameList = new String[mFileList.length];
			}

			// 見つかったファイルの分だけ追加する
			if( mFileList != null ) for (File currFile : mFileList) {
				// ディレクトリだった
				if (currFile.isDirectory()) {
					// 最後に/を加えてディレクトリの表示を
					fileNameList[itemCount] = currFile.getName() + "/";
				// ファイルだった
				} else {
					fileNameList[itemCount] = currFile.getName();
				}
				itemCount++;
			}

			// ダイアログを表示する
			new AlertDialog.Builder(mParent)
				.setTitle(dir)
				.setItems(fileNameList, this)
				.show();

		} catch (SecurityException se) {
			if( bDebug ) Log.e("SecurityException", se.getMessage());
		} catch (Exception e) {
			if( bDebug ) Log.e("Exception", e.getMessage());
		}
	}
}

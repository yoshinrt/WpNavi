package jp.dds.MtkUtility;

public interface MtkDriverCallback {
	final int GET_RECORD_SIZE	= 0;
	final int GET_FAILED_SECTOR	= 1;
	final int GET_INTERVAL		= 2;
	final int SET_INTERVAL		= 3;
	final int GET_LOG			= 4;
	final int GET_LOG_PARTIAL	= 5;
	final int OPEN				= 6;
	void onComplete( int iReason, int iParam );
}

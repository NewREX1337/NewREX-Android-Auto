// NewREX Android Auto — Copyright (C) 2026 NewREX — GPL-3.0-or-later (bkz. NOTICE.md)
package com.newrex.auto;

import android.view.Surface;

/** Shizuku'nun "shell" yetkisiyle çalışan yardımcı süreç (ShellService). */
interface IShellService {
    /** Shizuku'nun süreci kapatmak için kullandığı sabit kod. */
    void destroy() = 16777114;

    /** Kabuk komutu çalıştırır, çıktıyı döndürür. */
    String exec(String cmd) = 1;

    /** Verilen ekrana dokunma olayı gönderir. */
    boolean touch(int action, float x, float y, long downTime, long eventTime, int displayId) = 2;

    /** Verilen ekrana tuş (ör. geri) gönderir. */
    void key(int keyCode, int displayId) = 3;

    /**
     * NewREX'in araç ekranını "shell" yetkisiyle oluşturur: güvenilir ve telefon kilitliyken de açık kalan sanal
     * ekran. Ekran numarasını döndürür; olmazsa -1.
     */
    int createDisplay(String name, int width, int height, int dpi, in Surface surface) = 4;
    void resizeDisplay(int width, int height, int dpi) = 5;
    void setDisplaySurface(in Surface surface) = 6;
    void releaseDisplay() = 7;

    /** Araç ekranı sanal cihazla mı kuruldu (telefon ekranı kapanınca da uyumaz)? */
    boolean displayStaysAwake() = 8;
}

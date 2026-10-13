package jo.codeeditor.view.icons;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
@RunWith(RobolectricTestRunner.class)
public class EyeDiagTest {
    @Test
    public void diag() {
        // 1. Path brut, sans transformation
        Bitmap b1 = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888);
        Canvas c1 = new Canvas(b1);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(0xFFFFFFFF);
        c1.drawPath(EditorIcons.path(EditorIcons.Icon.UNDO), p);
        System.out.println("DIAG raw=" + count(b1));

        // 2. Avec translate/scale/translate
        Bitmap b2 = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888);
        Canvas c2 = new Canvas(b2);
        c2.save();
        c2.translate(32, 32);
        c2.scale(2f, 2f);
        c2.translate(-12, -12);
        c2.drawPath(EditorIcons.path(EditorIcons.Icon.UNDO), p);
        c2.restore();
        System.out.println("DIAG transformed=" + count(b2));

        // 3. draw() de EditorIcons
        Bitmap b3 = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888);
        Canvas c3 = new Canvas(b3);
        EditorIcons.draw(c3, EditorIcons.Icon.UNDO, 32, 32, 48, p);
        System.out.println("DIAG viaDraw=" + count(b3));

        // 4. Simple rect pour confirmer le raster
        Bitmap b4 = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888);
        Canvas c4 = new Canvas(b4);
        c4.drawRect(10, 10, 50, 50, p);
        System.out.println("DIAG rect=" + count(b4));

        // 5. Simple triangle à la main
        Path tri = new Path();
        tri.moveTo(32, 10); tri.lineTo(54, 50); tri.lineTo(10, 50); tri.close();
        Bitmap b5 = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888);
        Canvas c5 = new Canvas(b5);
        c5.drawPath(tri, p);
        System.out.println("DIAG tri=" + count(b5));
    }
    private static int count(Bitmap b) {
        int lit = 0;
        for (int y = 0; y < 64; y++) for (int x = 0; x < 64; x++) if (b.getPixel(x, y) != 0) lit++;
        return lit;
    }
}

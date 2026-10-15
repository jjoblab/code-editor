package jo.codeeditor.view;

import android.os.Parcel;
import android.os.Parcelable;
import android.view.View;

/**
 * État sauvegardé de la vue (scroll/zoom/wrap) — extrait d'EditorView
 * (A-01, audit 3.69.0) : l'état Parcelable vit dans sa classe, la vue
 * conserve les deux points d'attache View
 * ({@code onSaveInstanceState}/{@code onRestoreInstanceState}).
 *
 * <p>Champs package-private : seule la vue (et ses tests) les posent et
 * les lisent — le mécanisme View n'expose jamais cette classe au-delà
 * du package.</p>
 */
class EditorSavedState extends View.BaseSavedState {
    float vOffset;
    float hOffset;
    float fontScale;
    boolean wordWrap;

    EditorSavedState(Parcelable superState) {
        super(superState);
    }

    EditorSavedState(Parcel in) {
        super(in);
        vOffset = in.readFloat();
        hOffset = in.readFloat();
        fontScale = in.readFloat();
        wordWrap = in.readByte() != 0;
    }

    @Override
    public void writeToParcel(Parcel out, int flags) {
        super.writeToParcel(out, flags);
        out.writeFloat(vOffset);
        out.writeFloat(hOffset);
        out.writeFloat(fontScale);
        out.writeByte((byte) (wordWrap ? 1 : 0));
    }

    public static final Parcelable.Creator<EditorSavedState> CREATOR =
            new Parcelable.Creator<EditorSavedState>() {
        @Override
        public EditorSavedState createFromParcel(Parcel in) {
            return new EditorSavedState(in);
        }
        @Override
        public EditorSavedState[] newArray(int size) {
            return new EditorSavedState[size];
        }
    };
}

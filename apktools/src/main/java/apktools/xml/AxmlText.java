package apktools.xml;

/** Character-data child (from CDATA chunks, incl. whitespace). */
public final class AxmlText extends AxmlNode {
    public String text;

    public AxmlText(String text) {
        this.text = text == null ? "" : text;
    }
}

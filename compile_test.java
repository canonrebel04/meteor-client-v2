interface CharFilter { boolean filter(String text, char c); }
class CompileTest {
    public void run() {
        CharFilter f = (_, c) -> c != ' ';
    }
}

interface CharFilter { boolean filter(String text, char c); }
class CompileTest {
    public void run() {
        CharFilter f = (text, c) -> c != ' ';
    }
}

# -*- coding: utf-8 -*-
# ServerManagerPage: external-aware stop subtitle + room restore button.
import io

P = r"E:\game\gamea\mcl\HMCL\docs\plugin\server-manager\src\org\dsh\servermanager\ServerManagerPage.java"
s = io.open(P, encoding="utf-8").read()

def rep(old, new, label):
    global s
    assert old in s, "MISSING: " + label
    s = s.replace(old, new, 1)

# room restore button field
rep('''    private LineButton roomCodeButton;''',
'''    private LineButton roomCodeButton;
    private LineButton roomRestoreButton;''',
    "field")

# build the restore button inside the room section
rep('''        roomSection.getChildren().addAll(roomStatusLabel, roomCodeButton);
        roomSection.setVisible(false);
        roomSection.setManaged(false);
        return roomSection;''',
'''        roomRestoreButton = new LineButton();
        roomRestoreButton.setTitle("创建 / 恢复房间");
        roomRestoreButton.setSubtitle("服务端正在运行但房间尚未建立，点击重新扫描并创建房间码");
        roomRestoreButton.setOnAction(e -> {
            if (current != null)
                startRoomAfterStartup(current);
        });
        roomRestoreButton.setVisible(false);
        roomRestoreButton.setManaged(false);

        roomSection.getChildren().addAll(roomStatusLabel, roomCodeButton, roomRestoreButton);
        roomSection.setVisible(false);
        roomSection.setManaged(false);
        return roomSection;''',
    "restore button")

# refreshRoomUI: show the restore button in the Waiting/Unknown branch
rep('''        } else if (state instanceof TerracottaState.Waiting || state instanceof TerracottaState.Unknown) {
            roomStatusLabel.setText("尚未创建房间，停止并重新开设即可创建。");
            roomStatusLabel.setStyle("-fx-text-fill: #666;");
            roomCodeButton.setVisible(false);
            roomCodeButton.setManaged(false);
        } else {''',
'''        } else if (state instanceof TerracottaState.Waiting || state instanceof TerracottaState.Unknown) {
            roomStatusLabel.setText("服务端正在运行，但尚未建立联机房间：");
            roomStatusLabel.setStyle("-fx-text-fill: #666;");
            roomCodeButton.setVisible(false);
            roomCodeButton.setManaged(false);
            roomRestoreButton.setVisible(true);
            roomRestoreButton.setManaged(true);
        } else {''',
    "waiting branch")

# hide restore in other branches: add resets at the top of refreshRoomUI's visible path
rep('''        roomSection.setVisible(true);
        roomSection.setManaged(true);

        TerracottaState state = TerracottaManager.stateProperty().get();''',
'''        roomSection.setVisible(true);
        roomSection.setManaged(true);
        roomRestoreButton.setVisible(false);
        roomRestoreButton.setManaged(false);

        TerracottaState state = TerracottaManager.stateProperty().get();''',
    "reset restore")

# stop subtitle per external mode
rep('''    private void refreshStatus() {
        if (current == null)
            return;
        boolean running = service.isRunning(current);
        if (running) {''',
'''    private void refreshStatus() {
        if (current == null)
            return;
        ServerProcess process = service.getProcess(current);
        boolean external = process != null && process.isExternal();
        stopButton.setSubtitle(external
                ? "外部接管的服务端只能强制结束（可能丢失最近的改动）"
                : "向服务端控制台发送 stop 命令，30 秒后强制结束");
        boolean running = service.isRunning(current);
        if (running) {''',
    "refresh status")

io.open(P, "w", encoding="utf-8", newline="").write(s)
print("page patched")
